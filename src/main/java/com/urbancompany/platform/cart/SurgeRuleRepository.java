package com.urbancompany.platform.cart;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SurgeRuleRepository extends JpaRepository<SurgeRule, Long> { List<SurgeRule> findByActiveTrue(); }
