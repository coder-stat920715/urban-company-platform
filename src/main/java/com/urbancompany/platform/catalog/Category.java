package com.urbancompany.platform.catalog;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "categories")
@Getter @Setter
public class Category extends BaseEntity {
    @Column(name = "parent_id") private Long parentId;
    private String name;
    private String slug;
    @Column(name = "icon_url") private String iconUrl;
    @Column(name = "sort_order") private int sortOrder;
    private boolean active = true;
}
