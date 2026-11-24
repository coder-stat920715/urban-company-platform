package com.urbancompany.platform.dispatch;

import com.urbancompany.platform.booking.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;

public interface JobOfferRepository extends JpaRepository<JobOffer, Long> {
    List<JobOffer> findByBookingId(Long bookingId);
    List<JobOffer> findByBookingIdAndStatus(Long bookingId, OfferStatus status);
    boolean existsByBookingIdAndStatus(Long bookingId, OfferStatus status);
    List<JobOffer> findByStatus(OfferStatus status);
    List<JobOffer> findByStatusAndExpiresAtBefore(OfferStatus status, Instant t);
    List<JobOffer> findByPartnerIdAndStatusOrderByIdDesc(Long partnerId, OfferStatus status);

    @Query("select b.id from Booking b where b.status = ?1 and (b.nextDispatchAt is null or b.nextDispatchAt <= ?2) "
         + "and not exists (select o.id from JobOffer o where o.bookingId = b.id and o.status = ?3)")
    List<Long> findSearchingDue(BookingStatus status, Instant now, OfferStatus open);
}
