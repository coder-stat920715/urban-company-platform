package com.urbancompany.platform.catalog;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "services")
@Getter @Setter
public class ServiceOffering extends BaseEntity {
    @Column(name = "category_id") private Long categoryId;
    private String name;
    private String slug;
    @Column(columnDefinition = "text") private String description;
    private boolean active = true;
}
