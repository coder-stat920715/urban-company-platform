package com.urbancompany.platform.review;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface WarrantyClaimRepository extends JpaRepository<WarrantyClaim, Long> {
    boolean existsByBookingIdAndStatus(Long bookingId, String status);
    List<WarrantyClaim> findByCustomerIdOrderByIdDesc(Long customerId);
    List<WarrantyClaim> findByStatusOrderByIdDesc(String status);
}
