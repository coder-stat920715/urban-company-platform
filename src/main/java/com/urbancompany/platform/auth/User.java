package com.urbancompany.platform.auth;

import com.urbancompany.platform.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "users")
@Getter @Setter
public class User extends BaseEntity {
    private String phone;
    private String email;
    @Column(name = "password_hash") private String passwordHash;
    @Column(name = "full_name") private String fullName;
    @Enumerated(EnumType.STRING) private Role role;
    private String status = "ACTIVE";
    @Column(name = "phone_verified") private boolean phoneVerified;
    @Version private Long version;
}
