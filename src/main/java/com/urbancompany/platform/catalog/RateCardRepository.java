package com.urbancompany.platform.catalog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface RateCardRepository extends JpaRepository<RateCard, Long> {
List<RateCard> findByServiceIdAndActiveTrueOrderByItemName(Long serviceId);
}
