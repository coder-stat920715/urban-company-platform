package com.urbancompany.platform.partner;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "partner_documents")
@Getter @Setter
public class PartnerDocument extends BaseEntity {
    @Column(name = "partner_id") private Long partnerId;
    @Column(name = "doc_type") private String docType;     // GOVT_ID, CERTIFICATION, ADDRESS_PROOF, PHOTO
    @Column(name = "doc_number") private String docNumber;
    @Column(name = "file_url") private String fileUrl;
    private String status = "PENDING";                      // PENDING, VERIFIED, REJECTED
    private String remarks;
    @Column(name = "reviewed_by") private Long reviewedBy;
}
