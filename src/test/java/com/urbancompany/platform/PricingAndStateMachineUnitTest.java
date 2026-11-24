package com.urbancompany.platform;

import com.urbancompany.platform.booking.*;
import com.urbancompany.platform.cart.*;
import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.DomainEventPublisher;
import com.urbancompany.platform.common.GeoUtils;
import com.urbancompany.platform.config.AppProperties;
import com.urbancompany.platform.dispatch.Candidate;
import com.urbancompany.platform.dispatch.WeightedPartnerScoring;
import com.urbancompany.platform.partner.Partner;
import com.urbancompany.platform.payment.MembershipPlan;
import com.urbancompany.platform.payment.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PricingAndStateMachineUnitTest {

    static AppProperties props() {
        return new AppProperties("Asia/Kolkata", 30, false,
                new AppProperties.Jwt("x".repeat(40), 15, 7), new AppProperties.Otp(true, 300, 5), new AppProperties.Admin("a@a.com", "p", "+910000000000"),
                new AppProperties.Pricing(new BigDecimal("49.00"), new BigDecimal("99.00"), new BigDecimal("18.00"), new BigDecimal("5.00")),
                new AppProperties.Slots(LocalTime.of(8, 0), LocalTime.of(20, 0), 30, 30, 60, 7, 15),
                new AppProperties.Dispatch(30, List.of(2, 5, 10), 10, 10, 90, 3, 0.4, 0.3, 0.2, 0.1),
                new AppProperties.Payment("secret"), new AppProperties.Earnings(new BigDecimal("1.00"), new BigDecimal("500")));
    }

    // 2026-11-24 is a Tuesday
    static Instant ist(int hour) { return ZonedDateTime.of(2026, 11, 24, hour, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant(); }

    SurgeRuleRepository rules = mock(SurgeRuleRepository.class);
    MembershipService memberships = mock(MembershipService.class);
    CouponService coupons = mock(CouponService.class);
    PricingEngine engine;

    @BeforeEach
    void setUp() {
        SurgeRule evening = new SurgeRule();
        evening.setName("Evening peak"); evening.setStartHour(18); evening.setEndHour(21); evening.setMultiplier(new BigDecimal("1.20"));
        when(rules.findByActiveTrue()).thenReturn(List.of(evening));
        when(memberships.activePlan(any())).thenReturn(Optional.empty());
        AppProperties p = props();
        engine = new PricingEngine(List.of(new SurgePricingStrategy(rules, p), new InstantSurchargeStrategy(p), new MembershipDiscountStrategy(memberships),
                new CouponPricingStrategy(coupons), new ConvenienceFeeStrategy(p), new TaxPricingStrategy(p)));
    }

    private PriceBreakdown price(int hour, boolean instant, String coupon) {
        return engine.price(new PricingContext(1L, 1L, ist(hour), instant, new BigDecimal("1000.00"), coupon));
    }

    // ---------------- pricing strategies ----------------
    @Test void offPeakHasOnlyConvenienceFeeAndGst() {
        PriceBreakdown b = price(14, false, null);
        assertEquals(0, b.getSurgeAmount().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("49.00"), b.getConvenienceFee());
        assertEquals(new BigDecimal("188.82"), b.getTax());       // 18% of 1049
        assertEquals(new BigDecimal("1237.82"), b.getTotal());
    }

    @Test void eveningPeakAddsTwentyPercentSurge() {
        PriceBreakdown b = price(19, false, null);
        assertEquals(new BigDecimal("200.00"), b.getSurgeAmount());
        assertEquals(new BigDecimal("1473.82"), b.getTotal());    // (1000+200+49) * 1.18
    }

    @Test void ucPlusGivesDiscountAndWaivesConvenienceFee() {
        MembershipPlan plan = new MembershipPlan();
        plan.setName("UC Plus"); plan.setDiscountPercent(new BigDecimal("10.00")); plan.setZeroConvenienceFee(true);
        when(memberships.activePlan(any())).thenReturn(Optional.of(plan));
        PriceBreakdown b = price(19, false, null);
        assertEquals(new BigDecimal("100.00"), b.getMembershipDiscount());
        assertEquals(0, b.getConvenienceFee().compareTo(BigDecimal.ZERO));
        assertEquals(new BigDecimal("1298.00"), b.getTotal());    // (1000+200-100) * 1.18
    }

    @Test void couponReducesTaxableBase() {
        when(coupons.discountFor(eq("SAVE100"), any(), any(), any())).thenReturn(new BigDecimal("100.00"));
        PriceBreakdown b = price(14, false, "SAVE100");
        assertEquals(new BigDecimal("100.00"), b.getCouponDiscount());
        assertEquals("SAVE100", b.getCouponCode());
        assertEquals(new BigDecimal("1119.82"), b.getTotal());    // (1000-100+49) * 1.18
    }

    @Test void invalidCouponPropagatesBusinessError() {
        when(coupons.discountFor(any(), any(), any(), any())).thenThrow(BusinessException.unprocessable("COUPON_EXPIRED", "Coupon has expired"));
        BusinessException e = assertThrows(BusinessException.class, () -> price(14, false, "OLD"));
        assertEquals("COUPON_EXPIRED", e.getCode());
    }

    @Test void instantServiceAddsSurcharge() {
        PriceBreakdown b = price(14, true, null);
        assertEquals(new BigDecimal("99.00"), b.getSurgeAmount());
        assertEquals(new BigDecimal("1354.64"), b.getTotal());    // (1000+99+49) * 1.18
    }

    // ---------------- state pattern ----------------
    @Test void happyPathTransitionsAreLegal() {
        BookingStatus[] path = {BookingStatus.DRAFT, BookingStatus.PAYMENT_PENDING, BookingStatus.CONFIRMED, BookingStatus.SEARCHING_PARTNER,
                BookingStatus.PARTNER_ASSIGNED, BookingStatus.PARTNER_EN_ROUTE, BookingStatus.ARRIVED_OTP_VERIFIED, BookingStatus.IN_PROGRESS,
                BookingStatus.COMPLETED, BookingStatus.SETTLED};
        for (int i = 0; i < path.length - 1; i++) assertTrue(path[i].canTransitionTo(path[i + 1]), path[i] + " -> " + path[i + 1]);
    }

    @Test void extraWorkLoopAndDispute() {
        assertTrue(BookingStatus.IN_PROGRESS.canTransitionTo(BookingStatus.EXTRA_WORK_PENDING_APPROVAL));
        assertTrue(BookingStatus.EXTRA_WORK_PENDING_APPROVAL.canTransitionTo(BookingStatus.IN_PROGRESS));
        assertTrue(BookingStatus.COMPLETED.canTransitionTo(BookingStatus.DISPUTED));
        assertTrue(BookingStatus.DISPUTED.canTransitionTo(BookingStatus.SETTLED));
    }

    @Test void illegalTransitionsAreRejected() {
        assertFalse(BookingStatus.DRAFT.canTransitionTo(BookingStatus.IN_PROGRESS));
        assertFalse(BookingStatus.CONFIRMED.canTransitionTo(BookingStatus.COMPLETED));
        assertFalse(BookingStatus.IN_PROGRESS.canTransitionTo(BookingStatus.CANCELLED));   // cannot cancel mid-job
        assertFalse(BookingStatus.SETTLED.canTransitionTo(BookingStatus.IN_PROGRESS));
    }

    @Test void cancelledIsTerminal() {
        assertTrue(BookingStatus.CANCELLED.isTerminal());
        for (BookingStatus s : BookingStatus.values()) assertFalse(BookingStatus.CANCELLED.canTransitionTo(s));
    }

    @Test void actorGuardsLimitWhoCanTriggerWhat() {
        assertTrue(BookingStateMachine.isAllowed(Actor.CUSTOMER, BookingStatus.CONFIRMED, BookingStatus.CANCELLED));
        assertFalse(BookingStateMachine.isAllowed(Actor.PARTNER, BookingStatus.CONFIRMED, BookingStatus.CANCELLED));
        assertFalse(BookingStateMachine.isAllowed(Actor.CUSTOMER, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED));
        assertTrue(BookingStateMachine.isAllowed(Actor.PARTNER, BookingStatus.IN_PROGRESS, BookingStatus.COMPLETED));
        assertTrue(BookingStateMachine.isAllowed(Actor.CUSTOMER, BookingStatus.EXTRA_WORK_PENDING_APPROVAL, BookingStatus.IN_PROGRESS));
        assertFalse(BookingStateMachine.isAllowed(Actor.PARTNER, BookingStatus.EXTRA_WORK_PENDING_APPROVAL, BookingStatus.IN_PROGRESS));
        assertTrue(BookingStateMachine.isAllowed(Actor.ADMIN, BookingStatus.DISPUTED, BookingStatus.CANCELLED));
    }

    @Test void stateMachineAppliesTransitionAndEmitsOutboxEvent() {
        JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        DomainEventPublisher events = mock(DomainEventPublisher.class);
        BookingStateMachine sm = new BookingStateMachine(jdbc, events);
        Booking b = new Booking();
        b.setId(7L); b.setCustomerId(1L); b.setBookingCode("UC1");
        b.setStatus(BookingStatus.CONFIRMED);
        sm.transition(b, BookingStatus.SEARCHING_PARTNER, Actor.SYSTEM, null, "test");
        assertEquals(BookingStatus.SEARCHING_PARTNER, b.getStatus());
        verify(events).publish(eq("uc.booking-events"), eq("BOOKING"), eq(7L), eq("BOOKING_STATUS_CHANGED"), any());
    }

    @Test void stateMachineRejectsInvalidAndUnauthorizedTransitions() {
        BookingStateMachine sm = new BookingStateMachine(mock(JdbcClient.class, RETURNS_DEEP_STUBS), mock(DomainEventPublisher.class));
        Booking b = new Booking();
        b.setId(1L); b.setStatus(BookingStatus.CONFIRMED);
        BusinessException invalid = assertThrows(BusinessException.class, () -> sm.transition(b, BookingStatus.COMPLETED, Actor.ADMIN, 1L, "x"));
        assertEquals("INVALID_TRANSITION", invalid.getCode());
        BusinessException denied = assertThrows(BusinessException.class, () -> sm.transition(b, BookingStatus.CANCELLED, Actor.PARTNER, 1L, "x"));
        assertEquals("FORBIDDEN", denied.getCode());
        assertEquals(BookingStatus.CONFIRMED, b.getStatus());
    }

    // ---------------- dispatch scoring + geo ----------------
    private Partner partner(double rating, int ratingCount, double acceptance, int activeJobs) {
        Partner p = new Partner();
        p.setRatingAvg(rating); p.setRatingCount(ratingCount); p.setAcceptanceRate(acceptance); p.setActiveJobs(activeJobs);
        return p;
    }

    @Test void scoringPrefersCloserBetterRatedLessLoadedPartners() {
        WeightedPartnerScoring s = new WeightedPartnerScoring(props());
        double strong = s.score(new Candidate(partner(4.9, 100, 0.95, 0), 0.8, 2));
        double weak = s.score(new Candidate(partner(3.8, 100, 0.60, 2), 1.9, 2));
        assertTrue(strong > weak);
        double near = s.score(new Candidate(partner(4.5, 10, 0.9, 0), 0.5, 5));
        double far = s.score(new Candidate(partner(4.5, 10, 0.9, 0), 4.5, 5));
        assertTrue(near > far);
    }

    @Test void geoHelpers() {
        assertEquals(0.0, GeoUtils.haversineKm(12.97, 77.59, 12.97, 77.59), 1e-9);
        double d = GeoUtils.haversineKm(12.9716, 77.5946, 12.9352, 77.6245);
        assertTrue(d > 4 && d < 7, "Bengaluru centre to Koramangala ~5km, was " + d);
        var square = GeoUtils.parsePolygon("12.0,77.0;12.0,78.0;13.0,78.0;13.0,77.0");
        assertTrue(GeoUtils.inPolygon(12.5, 77.5, square));
        assertFalse(GeoUtils.inPolygon(14.0, 77.5, square));
    }
}
