package com.urbancompany.platform.dispatch;

import com.urbancompany.platform.booking.*;
import com.urbancompany.platform.common.*;
import com.urbancompany.platform.config.AppProperties;
import com.urbancompany.platform.location.PartnerGeoIndex;
import com.urbancompany.platform.notification.RealtimePublisher;
import com.urbancompany.platform.partner.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Real-time matching & auto-dispatch.
 *  - Candidates come from the Redis GEO index, expanding the radius 2km -> 5km -> 10km.
 *  - Exactly ONE outstanding offer per booking (30s TTL); on reject/timeout it escalates to the next best partner.
 *  - Accept/expire/reassign for a booking are serialized by the Redisson lock "lock:booking:assign:{id}"; the Booking @Version is the DB-level backstop.
 *  - All DB work runs in short REQUIRES_NEW transactions so locks are released only after commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchService {
    private final BookingRepository bookings;
    private final JobOfferRepository offers;
    private final PartnerRepository partners;
    private final PartnerService partnerService;
    private final PartnerAvailabilityService availability;
    private final PartnerGeoIndex geo;
    private final PartnerTrackingService tracking;
    private final PartnerScoringStrategy scoring;
    private final BookingStateMachine sm;
    private final DomainEventPublisher events;
    private final RealtimePublisher realtime;
    private final RedissonClient redisson;
    private final ApplicationEventPublisher publisher;
    private final AppProperties props;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    // ------------------------------------------------------------------ search start
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBookingConfirmed(BookingConfirmedEvent e) { beginSearchIfDue(e.bookingId()); }

    public void beginSearchIfDue(Long bookingId) {
        Booking b = bookings.findById(bookingId).orElse(null);
        if (b == null || b.getStatus() != BookingStatus.CONFIRMED) return;
        if (b.isInstant() || !b.getScheduledStart().isAfter(Instant.now().plus(Duration.ofMinutes(props.dispatch().leadMinutes())))) beginSearch(bookingId);
    }

    public void beginSearch(Long bookingId) {
        Boolean started = tx().execute(s -> {
            Booking b = bookings.findById(bookingId).orElseThrow();
            if (b.getStatus() != BookingStatus.CONFIRMED) return false;
            b.setSearchStartedAt(Instant.now());
            b.setNextDispatchAt(null);
            sm.transition(b, BookingStatus.SEARCHING_PARTNER, Actor.SYSTEM, null, "Searching for a professional");
            bookings.save(b);
            return true;
        });
        if (Boolean.TRUE.equals(started)) dispatchNext(bookingId);
    }

    // ------------------------------------------------------------------ matching
    public void dispatchNext(Long bookingId) {
        RLock lock = redisson.getLock("lock:dispatch:" + bookingId);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 20, TimeUnit.SECONDS);
            if (!locked) return;                                   // another node is already dispatching this booking
            tx().executeWithoutResult(s -> doDispatch(bookingId));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { log.warn("dispatchNext failed for booking {}: {}", bookingId, e.toString()); }
        finally { if (locked && lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    private void doDispatch(Long bookingId) {
        Booking b = bookings.findById(bookingId).orElse(null);
        if (b == null || b.getStatus() != BookingStatus.SEARCHING_PARTNER) return;
        if (offers.existsByBookingIdAndStatus(bookingId, OfferStatus.OFFERED)) return;
        Instant now = Instant.now();
        List<JobOffer> history = offers.findByBookingId(bookingId);
        Set<Long> excluded = history.stream().filter(o -> o.getStatus() == OfferStatus.REJECTED || o.getStatus() == OfferStatus.ACCEPTED
                || (o.getStatus() == OfferStatus.EXPIRED && o.getOfferedAt().isAfter(now.minusSeconds(60)))).map(JobOffer::getPartnerId).collect(Collectors.toSet());
        Candidate best = null;
        int usedRadius = 0;
        for (int radius : props.dispatch().radiiKm()) {
            List<Candidate> cands = candidates(b.getLat(), b.getLng(), b.getCategoryId(), b.getScheduledStart(), b.getScheduledEnd(), b.getId(), radius, excluded);
            if (!cands.isEmpty()) { best = cands.stream().max(Comparator.comparingDouble(scoring::score)).get(); usedRadius = radius; break; }
        }
        if (best == null) {
            int window = props.dispatch().searchWindowMinutes() * (b.isInstant() ? 1 : 3);
            Instant started = b.getSearchStartedAt() == null ? now : b.getSearchStartedAt();
            if (now.isAfter(started.plus(Duration.ofMinutes(window)))) noPartnerFound(b);
            else { b.setNextDispatchAt(now.plusSeconds(props.dispatch().retrySeconds())); bookings.save(b); }
            return;
        }
        Partner p = best.partner();
        JobOffer o = new JobOffer();
        o.setBookingId(bookingId); o.setPartnerId(p.getId()); o.setScore(scoring.score(best)); o.setRadiusKm(usedRadius);
        o.setRoundNo(history.size() + 1); o.setOfferedAt(now); o.setExpiresAt(now.plusSeconds(props.dispatch().offerTtlSeconds()));
        o = offers.save(o);
        p.setOffersReceived(p.getOffersReceived() + 1);
        recalcAcceptance(p);
        partners.save(p);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("offerId", o.getId()); d.put("bookingId", bookingId); d.put("bookingCode", b.getBookingCode()); d.put("partnerId", p.getId()); d.put("partnerUserId", p.getUserId());
        d.put("distanceKm", Math.round(best.distanceKm() * 100) / 100.0); d.put("radiusKm", usedRadius); d.put("expiresAt", o.getExpiresAt().toString());
        events.publish(Topics.DISPATCH, "OFFER", o.getId(), "OFFER_CREATED", d);
        Map<String, Object> push = new LinkedHashMap<>(d);
        push.put("instant", b.isInstant()); push.put("categoryId", b.getCategoryId()); push.put("scheduledStart", b.getScheduledStart().toString());
        push.put("durationMinutes", b.getDurationMinutes()); push.put("lat", b.getLat()); push.put("lng", b.getLng());
        push.put("estimatedGross", b.getSubtotal().add(b.getSurgeAmount())); push.put("ttlSeconds", props.dispatch().offerTtlSeconds());
        RealtimePublisher.afterCommit(() -> realtime.toUser(p.getUserId(), "/queue/offers", push));
    }

    private void noPartnerFound(Booking b) {
        sm.transition(b, BookingStatus.CANCELLED, Actor.SYSTEM, null, "No professional available");
        b.setCancelledAt(Instant.now()); b.setCancelReason("No professional available"); b.setCancelledBy("SYSTEM");
        b.setRefundedAmount(b.getPaidAmount());
        bookings.save(b);
        if (b.getPaidAmount().signum() > 0) publisher.publishEvent(new BookingCancelledEvent(b.getId(), b.getCustomerId(), b.getPaidAmount(), "No professional available - full refund"));
        events.publish(Topics.DISPATCH, "BOOKING", b.getId(), "NO_PARTNER_FOUND", BookingStateMachine.eventData(b));
    }

    List<Candidate> candidates(double lat, double lng, Long categoryId, Instant start, Instant end, Long bookingId, int radiusKm, Set<Long> excluded) {
        List<PartnerGeoIndex.Nearby> near = geo.search(lat, lng, radiusKm).stream().filter(n -> !excluded.contains(n.partnerId())).toList();
        if (near.isEmpty()) return List.of();
        Map<Long, Partner> byId = partners.findAllById(near.stream().map(PartnerGeoIndex.Nearby::partnerId).toList()).stream().collect(Collectors.toMap(Partner::getId, x -> x));
        List<Candidate> out = new ArrayList<>();
        for (PartnerGeoIndex.Nearby n : near) {
            Partner p = byId.get(n.partnerId());
            if (p == null || !p.isOnline() || p.getStatus() != PartnerStatus.APPROVED) continue;
            if (p.getActiveJobs() >= props.dispatch().maxConcurrentJobs()) continue;
            if (!partnerService.skills(p.getId()).contains(categoryId)) continue;
            if (!availability.isAvailable(p.getId(), start, end, bookingId)) continue;
            out.add(new Candidate(p, n.distanceKm(), radiusKm));
        }
        return out;
    }

    /** Insta-Service pre-check: is any eligible partner within the first two radii (<= 5 km)? */
    public boolean hasInstantCapacity(double lat, double lng, Long categoryId, Instant start, Instant end) {
        for (int radius : props.dispatch().radiiKm().stream().limit(2).toList())
            if (!candidates(lat, lng, categoryId, start, end, null, radius, Set.of()).isEmpty()) return true;
        return false;
    }

    private void recalcAcceptance(Partner p) {
        p.setAcceptanceRate(p.getOffersReceived() == 0 ? 1.0 : Math.min(1.0, (double) p.getOffersAccepted() / p.getOffersReceived()));
    }

    // ------------------------------------------------------------------ partner responses
    public Map<String, Object> accept(Long offerId, Long partnerUserId) {
        JobOffer pre = offers.findById(offerId).orElseThrow(() -> BusinessException.notFound("Offer"));
        Partner me = partnerService.byUser(partnerUserId);
        if (!pre.getPartnerId().equals(me.getId())) throw BusinessException.forbidden("This offer is not for you");
        return withAssignLock(pre.getBookingId(), () -> tx().execute(s -> {
            JobOffer o = offers.findById(offerId).orElseThrow();
            Booking b = bookings.findById(o.getBookingId()).orElseThrow();
            Partner p = partners.findById(me.getId()).orElseThrow();
            Instant now = Instant.now();
            if (o.getStatus() != OfferStatus.OFFERED) throw BusinessException.conflict("OFFER_NOT_ACTIVE", "Offer is " + o.getStatus());
            if (now.isAfter(o.getExpiresAt())) { o.setStatus(OfferStatus.EXPIRED); o.setRespondedAt(now); offers.save(o); throw BusinessException.conflict("OFFER_EXPIRED", "Offer expired"); }
            if (b.getStatus() != BookingStatus.SEARCHING_PARTNER || b.getPartnerId() != null) throw BusinessException.conflict("BOOKING_NOT_AVAILABLE", "Job was taken or cancelled");
            if (availability.hasConflict(p.getId(), b.getScheduledStart(), b.getScheduledEnd(), b.getId())) throw BusinessException.conflict("SCHEDULE_CONFLICT", "You have an overlapping job");
            o.setStatus(OfferStatus.ACCEPTED); o.setRespondedAt(now); offers.save(o);
            p.setOffersAccepted(p.getOffersAccepted() + 1); p.setActiveJobs(p.getActiveJobs() + 1); recalcAcceptance(p); partners.save(p);
            b.setPartnerId(p.getId());
            sm.transition(b, BookingStatus.PARTNER_ASSIGNED, Actor.SYSTEM, partnerUserId, "Accepted by " + p.getDisplayName());
            bookings.save(b);
            tracking.bindActiveBooking(p.getId(), b.getId());
            Map<String, Object> d = BookingStateMachine.eventData(b);
            d.put("offerId", o.getId()); d.put("partnerName", p.getDisplayName()); d.put("partnerUserId", p.getUserId());
            events.publish(Topics.DISPATCH, "OFFER", o.getId(), "PARTNER_ASSIGNED", d);
            return Map.<String, Object>of("bookingId", b.getId(), "status", b.getStatus().name(), "offerId", o.getId());
        }));
    }

    public void reject(Long offerId, Long partnerUserId) {
        JobOffer pre = offers.findById(offerId).orElseThrow(() -> BusinessException.notFound("Offer"));
        Partner me = partnerService.byUser(partnerUserId);
        if (!pre.getPartnerId().equals(me.getId())) throw BusinessException.forbidden("This offer is not for you");
        withAssignLock(pre.getBookingId(), () -> tx().execute(s -> {
            JobOffer o = offers.findById(offerId).orElseThrow();
            if (o.getStatus() != OfferStatus.OFFERED) throw BusinessException.conflict("OFFER_NOT_ACTIVE", "Offer is " + o.getStatus());
            o.setStatus(OfferStatus.REJECTED); o.setRespondedAt(Instant.now()); offers.save(o);
            events.publish(Topics.DISPATCH, "OFFER", o.getId(), "OFFER_REJECTED", Map.of("offerId", o.getId(), "bookingId", o.getBookingId(), "partnerId", o.getPartnerId()));
            return null;
        }));
        dispatchNext(pre.getBookingId());       // escalate immediately
    }

    public List<Map<String, Object>> pendingOffers(Long partnerUserId) {
        Partner p = partnerService.byUser(partnerUserId);
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobOffer o : offers.findByPartnerIdAndStatusOrderByIdDesc(p.getId(), OfferStatus.OFFERED)) {
            if (o.getExpiresAt().isBefore(now)) continue;
            Booking b = bookings.findById(o.getBookingId()).orElse(null);
            if (b == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("offerId", o.getId()); m.put("bookingId", b.getId()); m.put("scheduledStart", b.getScheduledStart()); m.put("durationMinutes", b.getDurationMinutes());
            m.put("lat", b.getLat()); m.put("lng", b.getLng()); m.put("instant", b.isInstant()); m.put("expiresAt", o.getExpiresAt());
            m.put("secondsLeft", Math.max(0, Duration.between(now, o.getExpiresAt()).toSeconds()));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ timeouts / reassignment
    public void expireOffer(JobOffer offer) {
        Boolean expired = withAssignLock(offer.getBookingId(), () -> tx().execute(s -> {
            JobOffer o = offers.findById(offer.getId()).orElseThrow();
            if (o.getStatus() != OfferStatus.OFFERED || o.getExpiresAt().isAfter(Instant.now())) return false;
            o.setStatus(OfferStatus.EXPIRED); o.setRespondedAt(Instant.now()); offers.save(o);
            events.publish(Topics.DISPATCH, "OFFER", o.getId(), "OFFER_EXPIRED", Map.of("offerId", o.getId(), "bookingId", o.getBookingId(), "partnerId", o.getPartnerId()));
            return true;
        }));
        if (Boolean.TRUE.equals(expired)) dispatchNext(offer.getBookingId());
    }

    public void cancelOpenOffers(Long bookingId) {
        for (JobOffer o : offers.findByBookingIdAndStatus(bookingId, OfferStatus.OFFERED)) { o.setStatus(OfferStatus.CANCELLED); o.setRespondedAt(Instant.now()); offers.save(o); }
    }

    public void releaseByPartner(Long bookingId, Long partnerUserId, String reason) {
        Partner me = partnerService.byUser(partnerUserId);
        reassign(bookingId, Actor.PARTNER, partnerUserId, null, reason == null ? "Released by partner" : reason, me.getId());
    }

    public void adminReassign(Long bookingId, Long adminId, Long newPartnerId, String reason) {
        reassign(bookingId, Actor.ADMIN, adminId, newPartnerId, reason, null);
    }

    private void reassign(Long bookingId, Actor actor, Long actorId, Long newPartnerId, String reason, Long mustBePartnerId) {
        withAssignLock(bookingId, () -> tx().execute(s -> {
            Booking b = bookings.findById(bookingId).orElseThrow(() -> BusinessException.notFound("Booking"));
            if (mustBePartnerId != null && !mustBePartnerId.equals(b.getPartnerId())) throw BusinessException.forbidden("Job is not assigned to you");
            Set<BookingStatus> ok = Set.of(BookingStatus.SEARCHING_PARTNER, BookingStatus.PARTNER_ASSIGNED, BookingStatus.PARTNER_EN_ROUTE);
            if (!ok.contains(b.getStatus())) throw BusinessException.conflict("INVALID_STATE", "Cannot reassign a booking in status " + b.getStatus());
            cancelOpenOffers(bookingId);
            if (b.getPartnerId() != null) {
                Partner old = partners.findById(b.getPartnerId()).orElseThrow();
                old.setActiveJobs(Math.max(0, old.getActiveJobs() - 1));
                partners.save(old);
                tracking.clearActiveBooking(old.getId());
                b.setPartnerId(null);
                sm.transition(b, BookingStatus.SEARCHING_PARTNER, actor, actorId, reason);
            }
            b.setSearchStartedAt(Instant.now());
            b.setNextDispatchAt(null);
            if (newPartnerId != null) {
                Partner np = partners.findById(newPartnerId).orElseThrow(() -> BusinessException.notFound("Partner"));
                if (np.getStatus() != PartnerStatus.APPROVED) throw BusinessException.unprocessable("PARTNER_NOT_APPROVED", "Partner is not approved");
                if (!partnerService.skills(np.getId()).contains(b.getCategoryId())) throw BusinessException.unprocessable("SKILL_MISMATCH", "Partner lacks the required skill");
                JobOffer o = new JobOffer();
                o.setBookingId(bookingId); o.setPartnerId(np.getId()); o.setStatus(OfferStatus.ACCEPTED); o.setScore(0); o.setRadiusKm(0);
                o.setRoundNo(offers.findByBookingId(bookingId).size() + 1); o.setOfferedAt(Instant.now()); o.setExpiresAt(Instant.now()); o.setRespondedAt(Instant.now());
                offers.save(o);
                np.setActiveJobs(np.getActiveJobs() + 1);
                partners.save(np);
                b.setPartnerId(np.getId());
                sm.transition(b, BookingStatus.PARTNER_ASSIGNED, actor, actorId, "Manually assigned: " + reason);
                tracking.bindActiveBooking(np.getId(), bookingId);
                Map<String, Object> d = BookingStateMachine.eventData(b);
                d.put("partnerName", np.getDisplayName()); d.put("partnerUserId", np.getUserId());
                events.publish(Topics.DISPATCH, "BOOKING", bookingId, "PARTNER_ASSIGNED", d);
            }
            bookings.save(b);
            return null;
        }));
        dispatchNext(bookingId);     // no-op if a partner is already assigned
    }

    // ------------------------------------------------------------------ scheduler hooks
    public void tick() {
        Instant now = Instant.now();
        for (JobOffer o : offers.findByStatusAndExpiresAtBefore(OfferStatus.OFFERED, now)) expireOffer(o);
        for (Long id : offers.findSearchingDue(BookingStatus.SEARCHING_PARTNER, now, OfferStatus.OFFERED)) dispatchNext(id);
        for (Long id : bookings.findConfirmedDue(BookingStatus.CONFIRMED, now.plus(Duration.ofMinutes(props.dispatch().leadMinutes())))) beginSearch(id);
        // countdown ticks for open offers
        List<JobOffer> open = offers.findByStatus(OfferStatus.OFFERED);
        if (open.isEmpty()) return;
        Map<Long, Partner> ps = partners.findAllById(open.stream().map(JobOffer::getPartnerId).distinct().toList()).stream().collect(Collectors.toMap(Partner::getId, x -> x));
        for (JobOffer o : open) {
            Partner p = ps.get(o.getPartnerId());
            long left = Duration.between(now, o.getExpiresAt()).toSeconds();
            if (p != null && left >= 0) realtime.toUser(p.getUserId(), "/queue/offer-countdown", Map.of("offerId", o.getId(), "bookingId", o.getBookingId(), "secondsLeft", left));
        }
    }

    private <T> T withAssignLock(Long bookingId, java.util.function.Supplier<T> action) {
        RLock lock = redisson.getLock("lock:booking:assign:" + bookingId);
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 20, TimeUnit.SECONDS);
            if (!locked) throw BusinessException.conflict("BUSY", "Booking is being updated, please retry");
            return action.get();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw BusinessException.conflict("INTERRUPTED", "Interrupted"); }
        finally { if (locked && lock.isHeldByCurrentThread()) lock.unlock(); }
    }
}
