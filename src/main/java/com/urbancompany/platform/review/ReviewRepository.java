package com.urbancompany.platform.review;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ReviewRepository extends JpaRepository<Review, Long> {
    boolean existsByBookingId(Long bookingId);
    List<Review> findTop50ByPartnerIdOrderByIdDesc(Long partnerId);
}
