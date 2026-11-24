package com.urbancompany.platform.catalog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AddonQuestionRepository extends JpaRepository<AddonQuestion, Long> {
List<AddonQuestion> findByPackageIdOrderBySortOrder(Long packageId);
    List<AddonQuestion> findByPackageIdIn(Collection<Long> ids);
}
