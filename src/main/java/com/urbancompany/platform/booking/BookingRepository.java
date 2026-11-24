package com.urbancompany.platform.booking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Page<Booking> findByCustomerIdOrderByIdDesc(Long customerId, Pageable pageable);
    Page<Booking> findByPartnerIdOrderByIdDesc(Long partnerId, Pageable pageable);
    Page<Booking> findByStatusOrderByIdDesc(BookingStatus status, Pageable pageable);
    Page<Booking> findAllByOrderByIdDesc(Pageable pageable);
    List<Booking> findByStatusAndCreatedAtBefore(BookingStatus status, Instant cutoff);
    @Query("select b.id from Booking b where b.status = ?1 and (b.instant = true or b.scheduledStart <= ?2)")
    List<Long> findConfirmedDue(BookingStatus status, Instant startBefore);
}
