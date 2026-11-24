package com.urbancompany.platform.booking;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** State pattern: each status knows which statuses may follow it. */
public enum BookingStatus {
    DRAFT, PAYMENT_PENDING, CONFIRMED, SEARCHING_PARTNER, PARTNER_ASSIGNED, PARTNER_EN_ROUTE, ARRIVED_OTP_VERIFIED,
    IN_PROGRESS, EXTRA_WORK_PENDING_APPROVAL, COMPLETED, SETTLED, CANCELLED, DISPUTED;

    private static final Map<BookingStatus, Set<BookingStatus>> NEXT = new EnumMap<>(BookingStatus.class);
    static {
        NEXT.put(DRAFT, EnumSet.of(PAYMENT_PENDING, CANCELLED));
        NEXT.put(PAYMENT_PENDING, EnumSet.of(CONFIRMED, CANCELLED));
        NEXT.put(CONFIRMED, EnumSet.of(SEARCHING_PARTNER, CANCELLED));
        NEXT.put(SEARCHING_PARTNER, EnumSet.of(PARTNER_ASSIGNED, CANCELLED));
        NEXT.put(PARTNER_ASSIGNED, EnumSet.of(PARTNER_EN_ROUTE, SEARCHING_PARTNER, CANCELLED));
        NEXT.put(PARTNER_EN_ROUTE, EnumSet.of(ARRIVED_OTP_VERIFIED, SEARCHING_PARTNER, CANCELLED));
        NEXT.put(ARRIVED_OTP_VERIFIED, EnumSet.of(IN_PROGRESS, CANCELLED));
        NEXT.put(IN_PROGRESS, EnumSet.of(EXTRA_WORK_PENDING_APPROVAL, COMPLETED, DISPUTED));
        NEXT.put(EXTRA_WORK_PENDING_APPROVAL, EnumSet.of(IN_PROGRESS, DISPUTED));
        NEXT.put(COMPLETED, EnumSet.of(SETTLED, DISPUTED));
        NEXT.put(SETTLED, EnumSet.of(DISPUTED));
        NEXT.put(DISPUTED, EnumSet.of(SETTLED, CANCELLED));
        NEXT.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
    }

    public boolean canTransitionTo(BookingStatus target) { return NEXT.getOrDefault(this, Set.of()).contains(target); }
    public Set<BookingStatus> allowedNext() { return NEXT.getOrDefault(this, Set.of()); }
    public boolean isTerminal() { return allowedNext().isEmpty(); }
}
