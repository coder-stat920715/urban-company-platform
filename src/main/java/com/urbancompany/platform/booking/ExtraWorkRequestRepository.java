package com.urbancompany.platform.booking;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ExtraWorkRequestRepository extends JpaRepository<ExtraWorkRequest, Long> {
    List<ExtraWorkRequest> findByBookingIdOrderByIdDesc(Long bookingId);
    Optional<ExtraWorkRequest> findFirstByBookingIdAndStatus(Long bookingId, String status);
}
