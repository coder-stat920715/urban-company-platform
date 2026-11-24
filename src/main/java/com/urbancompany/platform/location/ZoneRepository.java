package com.urbancompany.platform.location;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ZoneRepository extends JpaRepository<Zone, Long> { List<Zone> findByActiveTrue(); List<Zone> findByCityId(Long cityId); }
