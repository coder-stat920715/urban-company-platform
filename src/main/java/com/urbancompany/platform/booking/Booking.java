package com.urbancompany.platform.booking;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Column names follow Spring's snake_case naming strategy (see V1__init_schema.sql). */
@Entity @Table(name = "bookings")
@Getter @Setter
public class Booking extends BaseEntity {
    private String bookingCode;
    private Long customerId;
    private Long partnerId;
    private Long addressId;
    private Long categoryId;
    private Long zoneId;
    @Enumerated(EnumType.STRING) private BookingStatus status = BookingStatus.DRAFT;
    private boolean instant;
    private Instant scheduledStart;
    private Instant scheduledEnd;
    private int durationMinutes;
    private double lat;
    private double lng;
    private String addressText;
    private BigDecimal subtotal = BigDecimal.ZERO;
    private BigDecimal surgeAmount = BigDecimal.ZERO;
    private BigDecimal membershipDiscount = BigDecimal.ZERO;
    private BigDecimal couponDiscount = BigDecimal.ZERO;
    private String couponCode;
    private BigDecimal convenienceFee = BigDecimal.ZERO;
    private BigDecimal tax = BigDecimal.ZERO;
    private BigDecimal total = BigDecimal.ZERO;
    private BigDecimal extraAmount = BigDecimal.ZERO;
    private BigDecimal walletUsed = BigDecimal.ZERO;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private String startOtp;
    private String endOtp;
    private Instant arrivedAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant cancelledAt;
    private String cancelReason;
    private String cancelledBy;
    private BigDecimal cancellationFee = BigDecimal.ZERO;
    private BigDecimal refundedAmount = BigDecimal.ZERO;
    private Long warrantyOfBookingId;
    private Instant warrantyExpiresAt;
    private Instant searchStartedAt;
    private Instant nextDispatchAt;
    /** Optimistic locking: concurrent state changes (e.g. customer cancel vs partner accept) cannot both win. */
    @Version private Long version;
}
