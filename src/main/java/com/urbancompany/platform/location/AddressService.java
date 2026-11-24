package com.urbancompany.platform.location;

import com.urbancompany.platform.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService {
    private final AddressRepository repo;
    private final ZoneService zones;

    public record AddressRequest(String label, String line1, String line2, String landmark, double lat, double lng, boolean makeDefault) {}

    public List<Address> list(Long userId) { return repo.findByUserIdOrderByDefaultAddressDescIdDesc(userId); }

    @Transactional
    public Address create(Long userId, AddressRequest r) {
        Zone z = zones.requireServiceable(r.lat(), r.lng());
        Address a = new Address();
        a.setUserId(userId);
        apply(a, r, z);
        boolean first = repo.findByUserIdOrderByDefaultAddressDescIdDesc(userId).isEmpty();
        if (r.makeDefault() || first) { repo.clearDefault(userId); a.setDefaultAddress(true); }
        return repo.save(a);
    }

    @Transactional
    public Address update(Long userId, Long id, AddressRequest r) {
        Address a = owned(userId, id);
        Zone z = zones.requireServiceable(r.lat(), r.lng());
        apply(a, r, z);
        if (r.makeDefault()) { repo.clearDefault(userId); a.setDefaultAddress(true); }
        return repo.save(a);
    }

    @Transactional
    public void delete(Long userId, Long id) { repo.delete(owned(userId, id)); }

    public Address owned(Long userId, Long id) {
        Address a = repo.findById(id).orElseThrow(() -> BusinessException.notFound("Address"));
        if (!a.getUserId().equals(userId)) throw BusinessException.forbidden("Not your address");
        return a;
    }

    private void apply(Address a, AddressRequest r, Zone z) {
        a.setLabel(r.label() == null ? "Home" : r.label());
        a.setLine1(r.line1());
        a.setLine2(r.line2());
        a.setLandmark(r.landmark());
        a.setLat(r.lat());
        a.setLng(r.lng());
        a.setZoneId(z.getId());
        a.setCityId(z.getCityId());
    }
}
