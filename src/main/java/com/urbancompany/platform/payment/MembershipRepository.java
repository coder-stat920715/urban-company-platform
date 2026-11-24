package com.urbancompany.platform.payment;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;
public interface MembershipRepository extends JpaRepository<Membership, Long> {
    Optional<Membership> findFirstByUserIdAndStatusAndEndAtAfterOrderByEndAtDesc(Long userId, String status, Instant now);
}
