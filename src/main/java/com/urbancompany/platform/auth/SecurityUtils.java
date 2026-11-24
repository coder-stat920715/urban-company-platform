package com.urbancompany.platform.auth;

import com.urbancompany.platform.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private SecurityUtils() {}
    public static AuthPrincipal principal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthPrincipal p)) throw BusinessException.unauthorized("Authentication required");
        return p;
    }
    public static Long userId() { return principal().userId(); }
}
