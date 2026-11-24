package com.urbancompany.platform.booking;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.DomainEventPublisher;
import com.urbancompany.platform.common.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Enforces legal transitions + who may trigger them, records history and emits a BOOKING_STATUS_CHANGED outbox event. */
@Component
@RequiredArgsConstructor
public class BookingStateMachine {
    private static final Set<BookingStatus> CUSTOMER_TARGETS = Set.of(BookingStatus.CANCELLED, BookingStatus.DISPUTED);
    private static final Set<BookingStatus> PARTNER_TARGETS = Set.of(BookingStatus.PARTNER_EN_ROUTE, BookingStatus.ARRIVED_OTP_VERIFIED,
            BookingStatus.IN_PROGRESS, BookingStatus.EXTRA_WORK_PENDING_APPROVAL, BookingStatus.COMPLETED, BookingStatus.SEARCHING_PARTNER);

    private final JdbcClient jdbc;
    private final DomainEventPublisher events;

    public static boolean isAllowed(Actor actor, BookingStatus from, BookingStatus to) {
        if (!from.canTransitionTo(to)) return false;
        return switch (actor) {
            case ADMIN, SYSTEM -> true;
            case CUSTOMER -> CUSTOMER_TARGETS.contains(to) || (from == BookingStatus.EXTRA_WORK_PENDING_APPROVAL && to == BookingStatus.IN_PROGRESS);
            case PARTNER -> PARTNER_TARGETS.contains(to) && !(from == BookingStatus.EXTRA_WORK_PENDING_APPROVAL && to == BookingStatus.IN_PROGRESS);
        };
    }

    /** Mutates the entity status (caller persists) and publishes the event; must run inside a transaction. */
    public void transition(Booking b, BookingStatus to, Actor actor, Long actorId, String note) {
        BookingStatus from = b.getStatus();
        if (!from.canTransitionTo(to))
            throw BusinessException.conflict("INVALID_TRANSITION", "Cannot move booking from " + from + " to " + to);
        if (!isAllowed(actor, from, to))
            throw BusinessException.forbidden(actor + " is not allowed to move booking from " + from + " to " + to);
        b.setStatus(to);
        jdbc.sql("INSERT INTO booking_status_history(booking_id, from_status, to_status, actor, actor_id, note) VALUES (?,?,?,?,?,?)")
                .param(b.getId()).param(from.name()).param(to.name()).param(actor.name()).param(actorId).param(note).update();
        Map<String, Object> d = eventData(b);
        d.put("from", from.name());
        d.put("to", to.name());
        d.put("actor", actor.name());
        events.publish(Topics.BOOKING, "BOOKING", b.getId(), "BOOKING_STATUS_CHANGED", d);
    }

    public static Map<String, Object> eventData(Booking b) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("bookingId", b.getId());
        d.put("bookingCode", b.getBookingCode());
        d.put("customerId", b.getCustomerId());
        d.put("partnerId", b.getPartnerId());
        d.put("status", b.getStatus().name());
        return d;
    }
}
