package com.urbancompany.platform.review;

import com.urbancompany.platform.booking.Booking;
import com.urbancompany.platform.booking.BookingService;
import com.urbancompany.platform.booking.BookingStatus;
import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.partner.Partner;
import com.urbancompany.platform.partner.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {
    private final ReviewRepository reviews;
    private final BookingService bookings;
    private final PartnerRepository partners;

    @Transactional
    public Review rate(Long customerId, Long bookingId, int rating, String comment) {
        if (rating < 1 || rating > 5) throw BusinessException.bad("INVALID_RATING", "Rating must be 1-5");
        Booking b = bookings.get(bookingId);
        if (!b.getCustomerId().equals(customerId)) throw BusinessException.forbidden("Not your booking");
        if (b.getStatus() != BookingStatus.COMPLETED && b.getStatus() != BookingStatus.SETTLED) throw BusinessException.conflict("NOT_COMPLETED", "You can rate only completed services");
        if (reviews.existsByBookingId(bookingId)) throw BusinessException.conflict("ALREADY_REVIEWED", "This booking is already reviewed");
        Review r = new Review();
        r.setBookingId(bookingId); r.setCustomerId(customerId); r.setPartnerId(b.getPartnerId()); r.setRating(rating); r.setComment(comment);
        r = reviews.save(r);
        Partner p = partners.findById(b.getPartnerId()).orElseThrow();
        p.setRatingAvg(Math.round(((p.getRatingAvg() * p.getRatingCount() + rating) / (p.getRatingCount() + 1)) * 100.0) / 100.0);
        p.setRatingCount(p.getRatingCount() + 1);
        partners.save(p);
        return r;
    }

    public List<Review> forPartner(Long partnerId) { return reviews.findTop50ByPartnerIdOrderByIdDesc(partnerId); }
}
