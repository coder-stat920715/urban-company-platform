package com.urbancompany.platform.booking;

import com.urbancompany.platform.cart.*;
import com.urbancompany.platform.cart.CartModels.CartLine;
import com.urbancompany.platform.common.*;
import com.urbancompany.platform.config.AppProperties;
import com.urbancompany.platform.dispatch.DispatchService;
import com.urbancompany.platform.location.Address;
import com.urbancompany.platform.location.AddressService;
import com.urbancompany.platform.partner.Partner;
import com.urbancompany.platform.partner.PartnerRepository;
import com.urbancompany.platform.partner.PartnerService;
import com.urbancompany.platform.partner.PartnerTrackingService;
import com.urbancompany.platform.payment.Payment;
import com.urbancompany.platform.payment.PaymentRepository;
import com.urbancompany.platform.payment.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {
    private static final SecureRandom RNG = new SecureRandom();
    private final BookingRepository bookings;
    private final BookingItemRepository items;
    private final ExtraWorkRequestRepository extras;
    private final CartService cart;
    private final PricingEngine pricing;
    private final CouponService coupons;
    private final AddressService addresses;
    private final SlotService slots;
    private final BookingStateMachine sm;
    private final DomainEventPublisher events;
    private final RedissonClient redisson;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher publisher;
    private final DispatchService dispatch;
    private final PartnerRepository partners;
    private final PartnerService partnerService;
    private final PartnerTrackingService tracking;
    private final WalletService wallets;
    private final PaymentRepository payments;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final AppProperties props;

    public record CheckoutRequest(Long addressId, Long categoryId, Instant scheduledStart, boolean instant, String couponCode) {}

    public Booking get(Long id) { return bookings.findById(id).orElseThrow(() -> BusinessException.notFound("Booking")); }

    // =====================================================================================
    // CHECKOUT (cart -> booking). Slot reservation is serialized per zone+category+day with a Redisson lock.
    // =====================================================================================
    public Booking checkout(Long userId, CheckoutRequest r) {
        List<CartLine> lines = cart.linesForCheckout(userId, r.categoryId());
        Long categoryId = lines.get(0).categoryId();
        Address a = addresses.owned(userId, r.addressId());
        int duration = lines.stream().mapToInt(CartLine::durationMinutes).sum();
        Instant start;
        if (r.instant()) {
            if (lines.stream().anyMatch(l -> !l.instantEligible())) throw BusinessException.unprocessable("NOT_INSTANT_ELIGIBLE", "Some items are not available for Insta-Service");
            start = Instant.now();
            if (!dispatch.hasInstantCapacity(a.getLat(), a.getLng(), categoryId, start, start.plus(Duration.ofMinutes(duration))))
                throw BusinessException.unprocessable("NO_PARTNERS_AVAILABLE", "No professionals are available nearby right now. Try scheduling a slot.");
        } else {
            start = r.scheduledStart();
            if (start == null) throw BusinessException.bad("SLOT_REQUIRED", "scheduledStart is required for scheduled bookings");
            Instant now = Instant.now();
            if (start.isBefore(now.plus(Duration.ofMinutes(props.slots().minLeadMinutes())))) throw BusinessException.unprocessable("SLOT_TOO_SOON", "Slot must be at least " + props.slots().minLeadMinutes() + " minutes from now");
            if (start.isAfter(now.plus(Duration.ofDays(props.slots().horizonDays() + 1)))) throw BusinessException.unprocessable("SLOT_TOO_FAR", "Slot is beyond the booking horizon");
        }
        Instant end = start.plus(Duration.ofMinutes(duration));
        String lockKey = "lock:slot:" + a.getZoneId() + ":" + categoryId + ":" + start.atZone(ZoneId.of(props.timezone())).toLocalDate();
        RLock lock = redisson.getLock(lockKey);
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) throw BusinessException.conflict("SLOT_BUSY", "Slot is being reserved by someone else, please retry");
            try {
                Booking b = tx.execute(s -> create(userId, r, a, lines, categoryId, start, end, duration));
                cart.removeCategory(userId, categoryId);
                return b;
            } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw BusinessException.conflict("INTERRUPTED", "Interrupted"); }
    }

    private Booking create(Long userId, CheckoutRequest r, Address a, List<CartLine> lines, Long categoryId, Instant start, Instant end, int duration) {
        if (!r.instant() && slots.capacityLeft(a.getZoneId(), categoryId, start, duration) <= 0)
            throw BusinessException.conflict("SLOT_UNAVAILABLE", "This slot is no longer available");
        BigDecimal subtotal = lines.stream().map(CartLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        PriceBreakdown p = pricing.price(new PricingContext(userId, a.getZoneId(), start, r.instant(), Money.r(subtotal), r.couponCode()));
        Booking b = new Booking();
        b.setBookingCode(newCode());
        b.setCustomerId(userId); b.setAddressId(a.getId()); b.setCategoryId(categoryId); b.setZoneId(a.getZoneId());
        b.setInstant(r.instant()); b.setScheduledStart(start); b.setScheduledEnd(end); b.setDurationMinutes(duration);
        b.setLat(a.getLat()); b.setLng(a.getLng()); b.setAddressText(a.getLabel() + ": " + a.getLine1() + (a.getLine2() == null ? "" : ", " + a.getLine2()));
        b.setSubtotal(p.getSubtotal()); b.setSurgeAmount(p.getSurgeAmount()); b.setMembershipDiscount(p.getMembershipDiscount());
        b.setCouponDiscount(p.getCouponDiscount()); b.setCouponCode(p.getCouponCode()); b.setConvenienceFee(p.getConvenienceFee());
        b.setTax(p.getTax()); b.setTotal(p.getTotal());
        b.setStartOtp(otp()); b.setEndOtp(otp());
        b = bookings.save(b);
        for (CartLine l : lines) {
            BookingItem i = new BookingItem();
            i.setBookingId(b.getId()); i.setPackageId(l.packageId()); i.setPackageName(l.packageName()); i.setQuantity(l.quantity());
            i.setOptionKeys(String.join(",", l.optionKeys())); i.setUnitPrice(l.unitPrice()); i.setLineTotal(l.lineTotal()); i.setDurationMinutes(l.durationMinutes());
            items.save(i);
        }
        sm.transition(b, BookingStatus.PAYMENT_PENDING, Actor.SYSTEM, null, "Checkout created");
        Map<String, Object> d = BookingStateMachine.eventData(b);
        d.put("total", b.getTotal());
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "BOOKING_CREATED", d);
        return bookings.save(b);
    }

    private String newCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("UC").append(LocalDate.now(ZoneId.of(props.timezone())).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")));
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        return sb.toString();
    }
    private String otp() { return String.format("%04d", RNG.nextInt(10_000)); }

    // =====================================================================================
    // PAYMENT CALLBACKS
    // =====================================================================================
    @Transactional
    public void onPaymentConfirmed(Long bookingId, BigDecimal amount, BigDecimal walletAmount) {
        Booking b = get(bookingId);
        if (b.getStatus() != BookingStatus.PAYMENT_PENDING) {
            // payment arrived after the hold expired / booking cancelled: return the money
            log.warn("Payment for booking {} arrived in status {} - refunding", bookingId, b.getStatus());
            b.setPaidAmount(b.getPaidAmount().add(amount));
            bookings.save(b);
            publisher.publishEvent(new BookingRefundEvent(bookingId, b.getCustomerId(), amount, "Payment received after booking expired"));
            return;
        }
        b.setPaidAmount(b.getPaidAmount().add(amount));
        b.setWalletUsed(b.getWalletUsed().add(walletAmount));
        sm.transition(b, BookingStatus.CONFIRMED, Actor.SYSTEM, null, "Payment confirmed");
        bookings.save(b);
        if (b.getCouponCode() != null) coupons.redeem(b.getCouponCode(), b.getCustomerId(), b.getId());
        publisher.publishEvent(new BookingConfirmedEvent(b.getId()));
    }

    /** Releases unpaid bookings after the payment hold window (and any wallet money held for a pending intent). */
    @Scheduled(fixedDelay = 60_000)
    public void expireUnpaid() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(props.slots().paymentHoldMinutes()));
        for (Booking b : bookings.findByStatusAndCreatedAtBefore(BookingStatus.PAYMENT_PENDING, cutoff)) {
            try { tx.executeWithoutResult(s -> cancelInternal(b.getId(), Actor.SYSTEM, null, "Payment not completed in time", null)); }
            catch (Exception e) { log.warn("Could not expire booking {}: {}", b.getId(), e.toString()); }
        }
    }

    // =====================================================================================
    // CANCELLATION
    // =====================================================================================
    @Transactional
    public Booking cancelByCustomer(Long userId, Long bookingId, String reason) {
        Booking b = get(bookingId);
        if (!b.getCustomerId().equals(userId)) throw BusinessException.forbidden("Not your booking");
        return cancelInternal(bookingId, Actor.CUSTOMER, userId, reason, null);
    }

    @Transactional
    public Booking cancelByAdmin(Long adminId, Long bookingId, String reason, BigDecimal feeOverride) {
        return cancelInternal(bookingId, Actor.ADMIN, adminId, reason, feeOverride);
    }

    private Booking cancelInternal(Long bookingId, Actor actor, Long actorId, String reason, BigDecimal feeOverride) {
        Booking b = get(bookingId);
        BigDecimal fee = feeOverride != null ? Money.r(feeOverride) : cancellationFee(b);
        fee = Money.min(fee, b.getPaidAmount());
        BigDecimal refund = Money.max(Money.ZERO, b.getPaidAmount().subtract(fee));
        Long assignedPartner = b.getPartnerId();
        sm.transition(b, BookingStatus.CANCELLED, actor, actorId, reason);
        b.setCancelledAt(Instant.now()); b.setCancelReason(reason); b.setCancelledBy(actor.name());
        b.setCancellationFee(fee); b.setRefundedAmount(refund);
        bookings.save(b);
        if (assignedPartner != null) {
            partners.findById(assignedPartner).ifPresent(p -> { p.setActiveJobs(Math.max(0, p.getActiveJobs() - 1)); partners.save(p); });
            tracking.clearActiveBooking(assignedPartner);
        }
        dispatch.cancelOpenOffers(bookingId);
        releaseWalletHolds(b);
        if (refund.signum() > 0) publisher.publishEvent(new BookingCancelledEvent(bookingId, b.getCustomerId(), refund, reason));
        Map<String, Object> d = BookingStateMachine.eventData(b);
        d.put("reason", reason); d.put("cancellationFee", fee); d.put("refund", refund); d.put("cancelledBy", actor.name());
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "BOOKING_CANCELLED", d);
        return b;
    }

    private void releaseWalletHolds(Booking b) {
        for (Payment p : payments.findByBookingIdAndStatus(b.getId(), "CREATED")) {
            p.setStatus("FAILED"); p.setFailureReason("Booking cancelled");
            payments.save(p);
            if (p.getWalletAmount().signum() > 0) wallets.credit(p.getUserId(), p.getWalletAmount(), "HOLD_RELEASED", p.getIntentId(), "Wallet hold released");
        }
    }

    /** Free before assignment / >2h ahead; 10% inside 2h; 25% (min 100) once the partner is travelling or on site. */
    public BigDecimal cancellationFee(Booking b) {
        BigDecimal paid = b.getPaidAmount();
        if (paid.signum() == 0) return Money.ZERO;
        return switch (b.getStatus()) {
            case DRAFT, PAYMENT_PENDING, CONFIRMED, SEARCHING_PARTNER -> Money.ZERO;
            case PARTNER_ASSIGNED -> Instant.now().isBefore(b.getScheduledStart().minus(Duration.ofHours(2))) ? Money.ZERO : Money.pct(b.getTotal(), BigDecimal.TEN);
            default -> Money.max(new BigDecimal("100.00"), Money.pct(b.getTotal(), new BigDecimal("25")));
        };
    }

    // =====================================================================================
    // PARTNER EXECUTION FLOW
    // =====================================================================================
    private Booking partnerBooking(Long partnerUserId, Long bookingId) {
        Partner p = partnerService.byUser(partnerUserId);
        Booking b = get(bookingId);
        if (!p.getId().equals(b.getPartnerId())) throw BusinessException.forbidden("This job is not assigned to you");
        return b;
    }

    @Transactional
    public Booking enRoute(Long partnerUserId, Long bookingId) {
        Booking b = partnerBooking(partnerUserId, bookingId);
        sm.transition(b, BookingStatus.PARTNER_EN_ROUTE, Actor.PARTNER, partnerUserId, "Partner on the way");
        return bookings.save(b);
    }

    @Transactional
    public Booking arrive(Long partnerUserId, Long bookingId) {
        Booking b = partnerBooking(partnerUserId, bookingId);
        if (b.getStatus() != BookingStatus.PARTNER_EN_ROUTE) throw BusinessException.conflict("INVALID_STATE", "Mark en-route first");
        b.setArrivedAt(Instant.now());
        bookings.save(b);
        Map<String, Object> d = BookingStateMachine.eventData(b);
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "PARTNER_ARRIVED", d);
        return b;
    }

    @Transactional
    public Booking verifyStartOtp(Long partnerUserId, Long bookingId, String otp) {
        Booking b = partnerBooking(partnerUserId, bookingId);
        if (b.getStatus() != BookingStatus.PARTNER_EN_ROUTE || b.getArrivedAt() == null) throw BusinessException.conflict("INVALID_STATE", "Arrive at the location first");
        checkOtp("start", b, b.getStartOtp(), otp);
        sm.transition(b, BookingStatus.ARRIVED_OTP_VERIFIED, Actor.PARTNER, partnerUserId, "Start OTP verified");
        return bookings.save(b);
    }

    @Transactional
    public Booking startWork(Long partnerUserId, Long bookingId) {
        Booking b = partnerBooking(partnerUserId, bookingId);
        sm.transition(b, BookingStatus.IN_PROGRESS, Actor.PARTNER, partnerUserId, "Work started");
        b.setStartedAt(Instant.now());
        return bookings.save(b);
    }

    @Transactional
    public ExtraWorkRequest requestExtraWork(Long partnerUserId, Long bookingId, String description, BigDecimal amount, int extraMinutes) {
        Booking b = partnerBooking(partnerUserId, bookingId);
        if (b.getStatus() != BookingStatus.IN_PROGRESS) throw BusinessException.conflict("INVALID_STATE", "Extra work can only be raised while the job is in progress");
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("50000")) > 0) throw BusinessException.bad("INVALID_AMOUNT", "Amount must be between 1 and 50000");
        ExtraWorkRequest x = new ExtraWorkRequest();
        x.setBookingId(bookingId); x.setPartnerId(b.getPartnerId()); x.setDescription(description); x.setAmount(Money.r(amount)); x.setExtraMinutes(Math.max(0, extraMinutes));
        x = extras.save(x);
        sm.transition(b, BookingStatus.EXTRA_WORK_PENDING_APPROVAL, Actor.PARTNER, partnerUserId, "Extra work quoted");
        bookings.save(b);
        Map<String, Object> d = BookingStateMachine.eventData(b);
        d.put("extraWorkId", x.getId()); d.put("description", description); d.put("amount", x.getAmount()); d.put("extraMinutes", x.getExtraMinutes());
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "EXTRA_WORK_REQUESTED", d);
        return x;
    }

    /** Customer approves/rejects the mid-job quote. Approved amounts are charged to the wallet when possible, otherwise collected on completion. */
    @Transactional
    public ExtraWorkRequest resolveExtraWork(Long customerId, Long bookingId, Long requestId, boolean approve) {
        Booking b = get(bookingId);
        if (!b.getCustomerId().equals(customerId)) throw BusinessException.forbidden("Not your booking");
        ExtraWorkRequest x = extras.findById(requestId).filter(e -> e.getBookingId().equals(bookingId)).orElseThrow(() -> BusinessException.notFound("Extra work request"));
        if (!"PENDING".equals(x.getStatus())) throw BusinessException.conflict("ALREADY_RESOLVED", "Request already " + x.getStatus());
        sm.transition(b, BookingStatus.IN_PROGRESS, Actor.CUSTOMER, customerId, approve ? "Extra work approved" : "Extra work rejected");
        x.setStatus(approve ? "APPROVED" : "REJECTED");
        x.setResolvedAt(Instant.now());
        String settlement = "NONE";
        if (approve) {
            b.setExtraAmount(b.getExtraAmount().add(x.getAmount()));
            b.setDurationMinutes(b.getDurationMinutes() + x.getExtraMinutes());
            b.setScheduledEnd(b.getScheduledEnd().plus(Duration.ofMinutes(x.getExtraMinutes())));
            if (wallets.balance(customerId).compareTo(x.getAmount()) >= 0) {
                wallets.debit(customerId, x.getAmount(), "EXTRA_WORK", b.getBookingCode(), "Extra work: " + x.getDescription());
                b.setPaidAmount(b.getPaidAmount().add(x.getAmount()));
                b.setWalletUsed(b.getWalletUsed().add(x.getAmount()));
                settlement = "WALLET";
            } else settlement = "COLLECT_ON_COMPLETION";
        }
        extras.save(x);
        bookings.save(b);
        Map<String, Object> d = BookingStateMachine.eventData(b);
        d.put("extraWorkId", x.getId()); d.put("approved", approve); d.put("amount", x.getAmount()); d.put("settlement", settlement);
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "EXTRA_WORK_RESOLVED", d);
        return x;
    }

    @Transactional
    public Booking complete(Long partnerUserId, Long bookingId, String endOtp) {
        Booking b = partnerBooking(partnerUserId, bookingId);
        if (b.getStatus() != BookingStatus.IN_PROGRESS) throw BusinessException.conflict("INVALID_STATE", "Job must be in progress (resolve pending extra work first)");
        checkOtp("end", b, b.getEndOtp(), endOtp);
        sm.transition(b, BookingStatus.COMPLETED, Actor.PARTNER, partnerUserId, "End OTP verified");
        b.setCompletedAt(Instant.now());
        b.setWarrantyExpiresAt(Instant.now().plus(Duration.ofDays(props.warrantyDays())));
        bookings.save(b);
        Partner p = partners.findById(b.getPartnerId()).orElseThrow();
        p.setActiveJobs(Math.max(0, p.getActiveJobs() - 1));
        p.setJobsCompleted(p.getJobsCompleted() + 1);
        partners.save(p);
        tracking.clearActiveBooking(p.getId());
        Map<String, Object> d = BookingStateMachine.eventData(b);
        d.put("grossAmount", Money.r(b.getSubtotal().add(b.getSurgeAmount()).add(b.getExtraAmount())).toPlainString());
        d.put("totalAmount", b.getTotal());
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "BOOKING_COMPLETED", d);
        return b;
    }

    private void checkOtp(String kind, Booking b, String expected, String given) {
        String key = "otp:attempts:" + kind + ":" + b.getId();
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) redis.expire(key, Duration.ofHours(1));
        if (n != null && n > 5) throw BusinessException.bad("OTP_LOCKED", "Too many wrong OTP attempts. Contact support.");
        if (expected == null || !expected.equals(given)) throw BusinessException.bad("OTP_INVALID", "Incorrect " + kind + " OTP");
        redis.delete(key);
    }

    @Transactional
    public void markSettled(Long bookingId) {
        Booking b = get(bookingId);
        if (b.getStatus() == BookingStatus.COMPLETED) { sm.transition(b, BookingStatus.SETTLED, Actor.SYSTEM, null, "Earnings settled"); bookings.save(b); }
    }

    // =====================================================================================
    // DISPUTES / ADMIN
    // =====================================================================================
    @Transactional
    public Booking dispute(Long customerId, Long bookingId, String reason) {
        Booking b = get(bookingId);
        if (!b.getCustomerId().equals(customerId)) throw BusinessException.forbidden("Not your booking");
        if (b.getWarrantyExpiresAt() != null && Instant.now().isAfter(b.getWarrantyExpiresAt())) throw BusinessException.unprocessable("DISPUTE_WINDOW_CLOSED", "Dispute window has closed");
        sm.transition(b, BookingStatus.DISPUTED, Actor.CUSTOMER, customerId, reason);
        bookings.save(b);
        Map<String, Object> d = BookingStateMachine.eventData(b);
        d.put("reason", reason);
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "BOOKING_DISPUTED", d);
        return b;
    }

    /** Admin resolves a dispute with an optional refund; the booking returns to SETTLED (or CANCELLED for a full cancellation). */
    @Transactional
    public Booking resolveDispute(Long adminId, Long bookingId, BigDecimal refundAmount, String note) {
        Booking b = get(bookingId);
        if (b.getStatus() != BookingStatus.DISPUTED) throw BusinessException.conflict("INVALID_STATE", "Booking is not disputed");
        sm.transition(b, BookingStatus.SETTLED, Actor.ADMIN, adminId, note);
        if (refundAmount != null && refundAmount.signum() > 0) {
            b.setRefundedAmount(b.getRefundedAmount().add(refundAmount));
            publisher.publishEvent(new BookingRefundEvent(bookingId, b.getCustomerId(), refundAmount, "Dispute resolved: " + note));
        }
        return bookings.save(b);
    }

    @Transactional
    public Booking refundOnly(Long adminId, Long bookingId, BigDecimal amount, String reason) {
        Booking b = get(bookingId);
        b.setRefundedAmount(b.getRefundedAmount().add(amount));
        publisher.publishEvent(new BookingRefundEvent(bookingId, b.getCustomerId(), amount, reason));
        return bookings.save(b);
    }

    /** Free re-service under warranty: zero-priced clone of the original booking. */
    @Transactional
    public Booking createWarrantyRebooking(Booking original, Instant preferredStart) {
        Booking b = new Booking();
        b.setBookingCode(newCode());
        b.setCustomerId(original.getCustomerId()); b.setAddressId(original.getAddressId()); b.setCategoryId(original.getCategoryId()); b.setZoneId(original.getZoneId());
        Instant start = preferredStart == null ? Instant.now().plus(Duration.ofHours(24)) : preferredStart;
        b.setScheduledStart(start); b.setScheduledEnd(start.plus(Duration.ofMinutes(original.getDurationMinutes()))); b.setDurationMinutes(original.getDurationMinutes());
        b.setLat(original.getLat()); b.setLng(original.getLng()); b.setAddressText(original.getAddressText());
        b.setStartOtp(otp()); b.setEndOtp(otp()); b.setWarrantyOfBookingId(original.getId());
        b = bookings.save(b);
        for (BookingItem it : items.findByBookingId(original.getId())) {
            BookingItem c = new BookingItem();
            c.setBookingId(b.getId()); c.setPackageId(it.getPackageId()); c.setPackageName(it.getPackageName() + " (warranty)"); c.setQuantity(it.getQuantity());
            c.setOptionKeys(it.getOptionKeys()); c.setUnitPrice(Money.ZERO); c.setLineTotal(Money.ZERO); c.setDurationMinutes(it.getDurationMinutes());
            items.save(c);
        }
        sm.transition(b, BookingStatus.PAYMENT_PENDING, Actor.SYSTEM, null, "Warranty rebooking");
        sm.transition(b, BookingStatus.CONFIRMED, Actor.SYSTEM, null, "Covered under warranty of " + original.getBookingCode());
        bookings.save(b);
        publisher.publishEvent(new BookingConfirmedEvent(b.getId()));
        return b;
    }

    // =====================================================================================
    // QUERIES / VIEWS
    // =====================================================================================
    public Map<String, Object> view(Long userId, boolean isCustomerView, Long bookingId) {
        Booking b = get(bookingId);
        return toView(b, isCustomerView);
    }

    public Map<String, Object> toView(Booking b, boolean includeOtps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId()); m.put("bookingCode", b.getBookingCode()); m.put("status", b.getStatus()); m.put("allowedNext", b.getStatus().allowedNext());
        m.put("instant", b.isInstant()); m.put("categoryId", b.getCategoryId()); m.put("scheduledStart", b.getScheduledStart()); m.put("scheduledEnd", b.getScheduledEnd());
        m.put("durationMinutes", b.getDurationMinutes()); m.put("address", b.getAddressText()); m.put("lat", b.getLat()); m.put("lng", b.getLng());
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("subtotal", b.getSubtotal()); price.put("surge", b.getSurgeAmount()); price.put("membershipDiscount", b.getMembershipDiscount());
        price.put("couponDiscount", b.getCouponDiscount()); price.put("couponCode", b.getCouponCode()); price.put("convenienceFee", b.getConvenienceFee());
        price.put("tax", b.getTax()); price.put("total", b.getTotal()); price.put("extraAmount", b.getExtraAmount()); price.put("paid", b.getPaidAmount());
        price.put("cancellationFee", b.getCancellationFee()); price.put("refunded", b.getRefundedAmount());
        m.put("pricing", price);
        if (includeOtps && b.getStatus().ordinal() < BookingStatus.COMPLETED.ordinal() && b.getStatus() != BookingStatus.CANCELLED) {
            m.put("startOtp", b.getStartOtp());
            m.put("endOtp", b.getEndOtp());
        }
        if (b.getPartnerId() != null) partners.findById(b.getPartnerId()).ifPresent(p ->
                m.put("partner", Map.of("id", p.getId(), "name", String.valueOf(p.getDisplayName()), "rating", p.getRatingAvg(), "jobsCompleted", p.getJobsCompleted())));
        m.put("items", items.findByBookingId(b.getId()));
        m.put("extraWork", extras.findByBookingIdOrderByIdDesc(b.getId()));
        m.put("history", jdbc.sql("SELECT from_status, to_status, actor, note, created_at FROM booking_status_history WHERE booking_id = ? ORDER BY id").param(b.getId()).query().listOfRows());
        m.put("warrantyExpiresAt", b.getWarrantyExpiresAt());
        return m;
    }

    public Map<String, Object> customerView(Long userId, Long bookingId) {
        Booking b = get(bookingId);
        if (!b.getCustomerId().equals(userId)) throw BusinessException.forbidden("Not your booking");
        return toView(b, true);
    }

    public Map<String, Object> partnerView(Long partnerUserId, Long bookingId) { return toView(partnerBooking(partnerUserId, bookingId), false); }

    public List<Map<String, Object>> customerBookings(Long userId, int page, int size) {
        return bookings.findByCustomerIdOrderByIdDesc(userId, PageRequest.of(page, size)).map(b -> toSummary(b)).getContent();
    }

    public List<Map<String, Object>> partnerJobs(Long partnerUserId, int page, int size) {
        Partner p = partnerService.byUser(partnerUserId);
        return bookings.findByPartnerIdOrderByIdDesc(p.getId(), PageRequest.of(page, size)).map(b -> toSummary(b)).getContent();
    }

    public List<Map<String, Object>> adminList(String status, int page, int size) {
        var pg = PageRequest.of(page, size);
        var res = status == null || status.isBlank() ? bookings.findAllByOrderByIdDesc(pg) : bookings.findByStatusOrderByIdDesc(BookingStatus.valueOf(status.toUpperCase()), pg);
        return res.map(b -> toView(b, false)).getContent();
    }

    private Map<String, Object> toSummary(Booking b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId()); m.put("bookingCode", b.getBookingCode()); m.put("status", b.getStatus()); m.put("scheduledStart", b.getScheduledStart());
        m.put("total", b.getTotal()); m.put("address", b.getAddressText()); m.put("instant", b.isInstant());
        return m;
    }
}
