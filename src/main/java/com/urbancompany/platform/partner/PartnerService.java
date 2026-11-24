package com.urbancompany.platform.partner;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.common.Db;
import com.urbancompany.platform.common.DomainEventPublisher;
import com.urbancompany.platform.common.Topics;
import com.urbancompany.platform.location.PartnerGeoIndex;
import com.urbancompany.platform.location.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PartnerService {
    private final PartnerRepository partners;
    private final PartnerDocumentRepository documents;
    private final ZoneRepository zones;
    private final JdbcClient jdbc;
    private final PartnerGeoIndex geo;
    private final DomainEventPublisher events;

    public record ProfileRequest(String displayName, Long zoneId, List<Long> skillCategoryIds, String bankAccountNo, String bankIfsc, String bankHolder) {}
    public record WorkingHour(int dayOfWeek, LocalTime startTime, LocalTime endTime) {}

    public Partner byUser(Long userId) { return partners.findByUserId(userId).orElseThrow(() -> BusinessException.notFound("Partner profile")); }
    public Partner byId(Long id) { return partners.findById(id).orElseThrow(() -> BusinessException.notFound("Partner")); }

    @Transactional
    public Partner onboard(Long userId, ProfileRequest r) {
        if (partners.findByUserId(userId).isPresent()) throw BusinessException.conflict("PROFILE_EXISTS", "Partner profile already exists");
        zones.findById(r.zoneId()).orElseThrow(() -> BusinessException.notFound("Zone"));
        Partner p = new Partner();
        p.setUserId(userId);
        applyProfile(p, r);
        p = partners.save(p);
        replaceSkills(p.getId(), r.skillCategoryIds());
        return p;
    }

    @Transactional
    public Partner updateProfile(Long userId, ProfileRequest r) {
        Partner p = byUser(userId);
        applyProfile(p, r);
        if (r.skillCategoryIds() != null) replaceSkills(p.getId(), r.skillCategoryIds());
        return partners.save(p);
    }

    private void applyProfile(Partner p, ProfileRequest r) {
        if (r.displayName() != null) p.setDisplayName(r.displayName());
        if (r.zoneId() != null) p.setZoneId(r.zoneId());
        if (r.bankAccountNo() != null) p.setBankAccountNo(r.bankAccountNo());
        if (r.bankIfsc() != null) p.setBankIfsc(r.bankIfsc());
        if (r.bankHolder() != null) p.setBankHolder(r.bankHolder());
    }

    @Transactional
    public void replaceSkills(Long partnerId, List<Long> categoryIds) {
        jdbc.sql("DELETE FROM partner_skills WHERE partner_id = ?").param(partnerId).update();
        if (categoryIds == null) return;
        for (Long c : categoryIds.stream().distinct().toList())
            jdbc.sql("INSERT INTO partner_skills(partner_id, category_id) VALUES (?, ?)").param(partnerId).param(c).update();
    }

    public List<Long> skills(Long partnerId) { return jdbc.sql("SELECT category_id FROM partner_skills WHERE partner_id = ?").param(partnerId).query(Long.class).list(); }

    // ---------- KYC ----------
    @Transactional
    public PartnerDocument addDocument(Long userId, String type, String number, String url) {
        Partner p = byUser(userId);
        PartnerDocument d = new PartnerDocument();
        d.setPartnerId(p.getId()); d.setDocType(type.toUpperCase()); d.setDocNumber(number); d.setFileUrl(url);
        return documents.save(d);
    }

    public List<PartnerDocument> documents(Long partnerId) { return documents.findByPartnerIdOrderByIdDesc(partnerId); }

    @Transactional
    public Partner submitForReview(Long userId) {
        Partner p = byUser(userId);
        if (p.getStatus() != PartnerStatus.PENDING_KYC && p.getStatus() != PartnerStatus.REJECTED)
            throw BusinessException.conflict("INVALID_STATE", "KYC already submitted");
        if (documents(p.getId()).stream().noneMatch(d -> "GOVT_ID".equals(d.getDocType()))) throw BusinessException.unprocessable("GOVT_ID_REQUIRED", "Upload a government ID first");
        if (skills(p.getId()).isEmpty()) throw BusinessException.unprocessable("SKILLS_REQUIRED", "Select at least one skill category");
        if (p.getZoneId() == null) throw BusinessException.unprocessable("ZONE_REQUIRED", "Select a service zone");
        p.setStatus(PartnerStatus.UNDER_REVIEW);
        return partners.save(p);
    }

    @Transactional
    public PartnerDocument reviewDocument(Long adminId, Long docId, boolean approve, String remarks) {
        PartnerDocument d = documents.findById(docId).orElseThrow(() -> BusinessException.notFound("Document"));
        d.setStatus(approve ? "VERIFIED" : "REJECTED");
        d.setRemarks(remarks);
        d.setReviewedBy(adminId);
        return documents.save(d);
    }

    @Transactional
    public Partner approve(Long partnerId) {
        Partner p = byId(partnerId);
        boolean idVerified = documents(partnerId).stream().anyMatch(d -> "GOVT_ID".equals(d.getDocType()) && "VERIFIED".equals(d.getStatus()));
        if (!idVerified) throw BusinessException.unprocessable("KYC_INCOMPLETE", "Government ID must be verified before approval");
        p.setStatus(PartnerStatus.APPROVED);
        partners.save(p);
        events.publish(Topics.PARTNER, "PARTNER", p.getId(), "KYC_STATUS_CHANGED", Map.of("partnerId", p.getId(), "userId", p.getUserId(), "status", "APPROVED"));
        return p;
    }

    @Transactional
    public Partner reject(Long partnerId, String reason) {
        Partner p = byId(partnerId);
        p.setStatus(PartnerStatus.REJECTED);
        partners.save(p);
        events.publish(Topics.PARTNER, "PARTNER", p.getId(), "KYC_STATUS_CHANGED", Map.of("partnerId", p.getId(), "userId", p.getUserId(), "status", "REJECTED", "reason", String.valueOf(reason)));
        return p;
    }

    @Transactional
    public Partner suspend(Long partnerId, String reason) {
        Partner p = byId(partnerId);
        p.setStatus(PartnerStatus.SUSPENDED);
        p.setOnline(false);
        partners.save(p);
        geo.remove(partnerId);
        events.publish(Topics.PARTNER, "PARTNER", p.getId(), "KYC_STATUS_CHANGED", Map.of("partnerId", p.getId(), "userId", p.getUserId(), "status", "SUSPENDED", "reason", String.valueOf(reason)));
        return p;
    }

    @Transactional
    public Partner reinstate(Long partnerId) {
        Partner p = byId(partnerId);
        p.setStatus(PartnerStatus.APPROVED);
        return partners.save(p);
    }

    // ---------- availability ----------
    @Transactional
    public Partner setOnline(Long userId, boolean online, Double lat, Double lng) {
        Partner p = byUser(userId);
        if (online && p.getStatus() != PartnerStatus.APPROVED) throw BusinessException.forbidden("Only approved partners can go online");
        p.setOnline(online);
        partners.save(p);
        if (online) { if (lat != null && lng != null) geo.update(p.getId(), lat, lng); }
        else geo.remove(p.getId());
        return p;
    }

    @Transactional
    public void replaceWorkingHours(Long userId, List<WorkingHour> hours) {
        Partner p = byUser(userId);
        for (WorkingHour h : hours) if (!h.endTime().isAfter(h.startTime())) throw BusinessException.bad("INVALID_HOURS", "End time must be after start time");
        jdbc.sql("DELETE FROM partner_working_hours WHERE partner_id = ?").param(p.getId()).update();
        for (WorkingHour h : hours)
            jdbc.sql("INSERT INTO partner_working_hours(partner_id, day_of_week, start_time, end_time) VALUES (?,?,?,?)")
                    .param(p.getId()).param(h.dayOfWeek()).param(java.sql.Time.valueOf(h.startTime())).param(java.sql.Time.valueOf(h.endTime())).update();
    }

    public List<Map<String, Object>> workingHours(Long userId) {
        return jdbc.sql("SELECT day_of_week, start_time, end_time FROM partner_working_hours WHERE partner_id = ? ORDER BY day_of_week, start_time")
                .param(byUser(userId).getId()).query().listOfRows();
    }

    @Transactional
    public void addLeave(Long userId, Instant from, Instant to, String reason) {
        if (!to.isAfter(from)) throw BusinessException.bad("INVALID_RANGE", "Leave end must be after start");
        jdbc.sql("INSERT INTO partner_leaves(partner_id, start_at, end_at, reason) VALUES (?,?,?,?)")
                .param(byUser(userId).getId()).param(Db.ts(from)).param(Db.ts(to)).param(reason).update();
    }

    public List<Map<String, Object>> leaves(Long userId) {
        return jdbc.sql("SELECT id, start_at, end_at, reason FROM partner_leaves WHERE partner_id = ? AND end_at > now() ORDER BY start_at")
                .param(byUser(userId).getId()).query().listOfRows();
    }

    @Transactional
    public void deleteLeave(Long userId, Long leaveId) {
        jdbc.sql("DELETE FROM partner_leaves WHERE id = ? AND partner_id = ?").param(leaveId).param(byUser(userId).getId()).update();
    }

    // ---------- scorecard ----------
    public Map<String, Object> scorecard(Long partnerId) {
        Partner p = byId(partnerId);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("partnerId", p.getId()); m.put("name", p.getDisplayName()); m.put("status", p.getStatus());
        m.put("ratingAvg", p.getRatingAvg()); m.put("ratingCount", p.getRatingCount());
        m.put("acceptanceRate", p.getAcceptanceRate()); m.put("jobsCompleted", p.getJobsCompleted()); m.put("activeJobs", p.getActiveJobs());
        Integer cancelled = jdbc.sql("SELECT count(*) FROM job_offers WHERE partner_id = ? AND status = 'EXPIRED'").param(partnerId).query(Integer.class).single();
        m.put("missedOffers", cancelled);
        return m;
    }
}
