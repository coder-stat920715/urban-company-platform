package com.urbancompany.platform.catalog;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "addon_questions")
@Getter @Setter
public class AddonQuestion extends BaseEntity {
    @Column(name = "package_id") private Long packageId;
    @Column(name = "question_key") private String questionKey;
    @Column(name = "question_text") private String questionText;
    @Enumerated(EnumType.STRING) private QuestionType type;
    private boolean required;
    @Column(name = "sort_order") private int sortOrder;
}
