package com.urbancompany.platform.auth;

import com.urbancompany.platform.common.BusinessException;
import com.urbancompany.platform.payment.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final OtpService otpService;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final WalletService wallets;

    public String requestOtp(String phone) { return otpService.send(phone); }

    @Transactional
    public JwtService.TokenPair verifyOtp(String phone, String otp, String fullName, Role requestedRole) {
        otpService.verify(phone, otp);
        User u = users.findByPhone(phone).orElseGet(() -> {
            User n = new User();
            n.setPhone(phone);
            n.setFullName(fullName);
            n.setRole(requestedRole == Role.PARTNER ? Role.PARTNER : Role.CUSTOMER); // ADMIN can never self-register
            return n;
        });
        u.setPhoneVerified(true);
        if (u.getFullName() == null && fullName != null) u.setFullName(fullName);
        u = users.save(u);
        ensureWallet(u);
        return jwt.issue(u);
    }

    @Transactional
    public JwtService.TokenPair register(String email, String password, String fullName, String phone, Role requestedRole) {
        if (users.existsByEmailIgnoreCase(email)) throw BusinessException.conflict("EMAIL_TAKEN", "Email already registered");
        if (phone != null && users.existsByPhone(phone)) throw BusinessException.conflict("PHONE_TAKEN", "Phone already registered");
        User u = new User();
        u.setEmail(email.toLowerCase());
        u.setPasswordHash(encoder.encode(password));
        u.setFullName(fullName);
        u.setPhone(phone);
        u.setRole(requestedRole == Role.PARTNER ? Role.PARTNER : Role.CUSTOMER);
        u = users.save(u);
        ensureWallet(u);
        return jwt.issue(u);
    }

    public JwtService.TokenPair login(String email, String password) {
        User u = users.findByEmailIgnoreCase(email).orElseThrow(() -> BusinessException.unauthorized("Invalid credentials"));
        if (u.getPasswordHash() == null || !encoder.matches(password, u.getPasswordHash())) throw BusinessException.unauthorized("Invalid credentials");
        if (!"ACTIVE".equals(u.getStatus())) throw BusinessException.forbidden("Account is blocked");
        return jwt.issue(u);
    }

    public JwtService.TokenPair refresh(String refreshToken) {
        Long userId = jwt.consumeRefresh(refreshToken);
        User u = users.findById(userId).orElseThrow(() -> BusinessException.unauthorized("User not found"));
        if (!"ACTIVE".equals(u.getStatus())) throw BusinessException.forbidden("Account is blocked");
        return jwt.issue(u);
    }

    public void logout(String refreshToken) { jwt.revoke(refreshToken); }

    private void ensureWallet(User u) { if (u.getRole() == Role.CUSTOMER) wallets.ensureWallet(u.getId()); }
}
