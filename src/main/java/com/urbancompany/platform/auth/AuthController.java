package com.urbancompany.platform.auth;

import com.urbancompany.platform.common.ApiResponse;
import com.urbancompany.platform.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService auth;
    private final UserRepository users;

    public record OtpRequest(@NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$") String phone) {}
    public record OtpVerifyRequest(@NotBlank String phone, @NotBlank String otp, String fullName, Role role) {}
    public record RegisterRequest(@Email @NotBlank String email, @NotBlank @Size(min = 8, max = 72) String password,
                                  @NotBlank String fullName, String phone, Role role) {}
    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/otp/request")
    public ApiResponse<Map<String, Object>> requestOtp(@Valid @RequestBody OtpRequest r) {
        String otp = auth.requestOtp(r.phone());
        return ApiResponse.ok("OTP sent", otp == null ? Map.of("sent", true) : Map.of("sent", true, "devOtp", otp));
    }

    @PostMapping("/otp/verify")
    public ApiResponse<JwtService.TokenPair> verifyOtp(@Valid @RequestBody OtpVerifyRequest r) {
        return ApiResponse.ok(auth.verifyOtp(r.phone(), r.otp(), r.fullName(), r.role()));
    }

    @PostMapping("/register")
    public ApiResponse<JwtService.TokenPair> register(@Valid @RequestBody RegisterRequest r) {
        return ApiResponse.ok(auth.register(r.email(), r.password(), r.fullName(), r.phone(), r.role()));
    }

    @PostMapping("/login")
    public ApiResponse<JwtService.TokenPair> login(@Valid @RequestBody LoginRequest r) {
        return ApiResponse.ok(auth.login(r.email(), r.password()));
    }

    @PostMapping("/refresh")
    public ApiResponse<JwtService.TokenPair> refresh(@Valid @RequestBody RefreshRequest r) {
        return ApiResponse.ok(auth.refresh(r.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest r) { auth.logout(r.refreshToken()); return ApiResponse.ok(null); }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        User u = users.findById(SecurityUtils.userId()).orElseThrow(() -> BusinessException.notFound("User"));
        return ApiResponse.ok(Map.of("id", u.getId(), "fullName", String.valueOf(u.getFullName()), "role", u.getRole(),
                "phone", String.valueOf(u.getPhone()), "email", String.valueOf(u.getEmail())));
    }
}
