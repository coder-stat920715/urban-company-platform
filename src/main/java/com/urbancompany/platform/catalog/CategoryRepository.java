package com.urbancompany.platform.catalog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CategoryRepository extends JpaRepository<Category, Long> {
List<Category> findByActiveTrueOrderBySortOrderAscNameAsc();
}
