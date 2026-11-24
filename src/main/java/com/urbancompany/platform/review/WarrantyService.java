package com.urbancompany.platform.review;

import com.urbancompany.platform.booking.Booking;
import com.urbancompany.platform.booking.BookingService;
import com.urbancompany.platform.booking.BookingStatus;
import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.DomainEventPublisher;
import com.urbancompany.platform.common.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 30-day service warranty: customer raises a claim; admin approves a free re-service, refunds, or rejects. */
@Service
@RequiredArgsConstructor
public class WarrantyService {
    private final WarrantyClaimRepository claims;
    private final BookingService bookings;
    private final DomainEventPublisher events;

    @Transactional
    public WarrantyClaim claim(Long customerId, Long bookingId, String reason, Instant preferredStart) {
        Booking b = bookings.get(bookingId);
        if (!b.getCustomerId().equals(customerId)) throw BusinessException.forbidden("Not your booking");
        if (b.getStatus() != BookingStatus.COMPLETED && b.getStatus() != BookingStatus.SETTLED) throw BusinessException.conflict("NOT_COMPLETED", "Warranty applies to completed services");
        if (b.getWarrantyExpiresAt() == null || Instant.now().isAfter(b.getWarrantyExpiresAt())) throw BusinessException.unprocessable("WARRANTY_EXPIRED", "The warranty period has ended");
        if (b.getWarrantyOfBookingId() != null) throw BusinessException.unprocessable("NO_NESTED_WARRANTY", "Warranty re-services are not covered again");
        if (claims.existsByBookingIdAndStatus(bookingId, "PENDING")) throw BusinessException.conflict("CLAIM_EXISTS", "A claim is already pending for this booking");
        WarrantyClaim c = new WarrantyClaim();
        c.setBookingId(bookingId); c.setCustomerId(customerId); c.setReason(reason); c.setPreferredStart(preferredStart);
        return claims.save(c);
    }

    public List<WarrantyClaim> mine(Long customerId) { return claims.findByCustomerIdOrderByIdDesc(customerId); }
    public List<WarrantyClaim> byStatus(String status) { return claims.findByStatusOrderByIdDesc(status == null ? "PENDING" : status); }

    @Transactional
    public WarrantyClaim resolve(Long adminId, Long claimId, String decision, String note, BigDecimal refundAmount) {
        WarrantyClaim c = claims.findById(claimId).orElseThrow(() -> BusinessException.notFound("Claim"));
        if (!"PENDING".equals(c.getStatus())) throw BusinessException.conflict("ALREADY_RESOLVED", "Claim already " + c.getStatus());
        Booking original = bookings.get(c.getBookingId());
        switch (decision.toUpperCase()) {
            case "APPROVE_REBOOK" -> { Booking nb = bookings.createWarrantyRebooking(original, c.getPreferredStart()); c.setRebookingId(nb.getId()); c.setStatus("APPROVED"); }
            case "REFUND" -> {
                if (refundAmount == null || refundAmount.signum() <= 0) throw BusinessException.bad("REFUND_AMOUNT_REQUIRED", "refundAmount is required");
                bookings.refundOnly(adminId, original.getId(), refundAmount, "Warranty refund: " + note); c.setStatus("REFUNDED");
            }
            case "REJECT" -> c.setStatus("REJECTED");
            default -> throw BusinessException.bad("INVALID_DECISION", "decision must be APPROVE_REBOOK, REFUND or REJECT");
        }
        c.setResolutionNote(note);
        claims.save(c);
        events.publish(Topics.BOOKING, "WARRANTY", c.getId(), "WARRANTY_RESOLVED",
                Map.of("customerId", c.getCustomerId(), "bookingId", c.getBookingId(), "decision", c.getStatus(), "note", String.valueOf(note)));
        return c;
    }
}
