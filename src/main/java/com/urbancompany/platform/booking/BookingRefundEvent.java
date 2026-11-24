package com.urbancompany.platform.booking;
import java.math.BigDecimal;
public record BookingRefundEvent(Long bookingId, Long customerId, BigDecimal amount, String reason) {}
