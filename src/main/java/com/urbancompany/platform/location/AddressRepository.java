package com.urbancompany.platform.location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
public interface AddressRepository extends JpaRepository<Address, Long> {
    List<Address> findByUserIdOrderByDefaultAddressDescIdDesc(Long userId);
    @Modifying @Query("update Address a set a.defaultAddress = false where a.userId = ?1")
    void clearDefault(Long userId);
}
