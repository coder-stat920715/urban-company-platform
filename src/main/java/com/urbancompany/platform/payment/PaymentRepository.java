package com.urbancompany.platform.payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByIntentId(String intentId);
    List<Payment> findByBookingIdOrderByIdDesc(Long bookingId);
    List<Payment> findByBookingIdAndStatus(Long bookingId, String status);
}
