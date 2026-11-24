package com.urbancompany.platform.partner;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface CommissionTierRepository extends JpaRepository<CommissionTier, Long> { List<CommissionTier> findAllByOrderByMinJobsDesc(); }
