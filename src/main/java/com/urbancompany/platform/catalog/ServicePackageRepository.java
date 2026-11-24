package com.urbancompany.platform.catalog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ServicePackageRepository extends JpaRepository<ServicePackage, Long> {
List<ServicePackage> findByServiceIdAndActiveTrueOrderByBasePrice(Long serviceId);
}
