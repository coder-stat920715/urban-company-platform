package com.urbancompany.platform.partner;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PartnerDocumentRepository extends JpaRepository<PartnerDocument, Long> { List<PartnerDocument> findByPartnerIdOrderByIdDesc(Long partnerId); }
