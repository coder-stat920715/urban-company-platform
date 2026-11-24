package com.urbancompany.platform.catalog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, Long> {
List<ServiceOffering> findByCategoryIdAndActiveTrueOrderByName(Long categoryId);
}
