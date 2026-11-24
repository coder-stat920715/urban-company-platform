package com.urbancompany.platform.catalog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AddonOptionRepository extends JpaRepository<AddonOption, Long> {
List<AddonOption> findByQuestionIdInAndActiveTrue(Collection<Long> ids);
    List<AddonOption> findByQuestionId(Long questionId);
}
