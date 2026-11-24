package com.urbancompany.platform.booking;
import java.math.BigDecimal;
public record BookingCancelledEvent(Long bookingId, Long customerId, BigDecimal refundAmount, String reason) {}
