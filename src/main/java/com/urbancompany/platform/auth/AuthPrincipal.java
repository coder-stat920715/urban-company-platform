package com.urbancompany.platform.auth;

import java.security.Principal;

public record AuthPrincipal(Long userId, Role role) implements Principal {
    @Override public String getName() { return String.valueOf(userId); }
}
