package com.urbancompany.platform.admin;

import com.urbancompany.platform.auth.Role;
import com.urbancompany.platform.auth.User;
import com.urbancompany.platform.auth.UserRepository;
import com.urbancompany.platform.config.AppProperties;
import com.urbancompany.platform.partner.*;
import com.urbancompany.platform.payment.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bootstraps the admin account and (when app.seed-demo=true) a demo customer + 3 approved partners in Koramangala.
 * Demo logins: customer@demo.local / partner1..3@demo.local, password Demo@12345.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements ApplicationRunner {
    private static final String DEMO_PASSWORD = "Demo@12345";
    private final UserRepository users;
    private final PartnerRepository partners;
    private final PartnerDocumentRepository documents;
    private final PartnerService partnerService;
    private final WalletService wallets;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final JdbcClient jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!users.existsByEmailIgnoreCase(props.admin().email())) {
            User a = new User();
            a.setEmail(props.admin().email()); a.setPhone(props.admin().phone()); a.setFullName("Platform Admin"); a.setRole(Role.ADMIN);
            a.setPasswordHash(encoder.encode(props.admin().password())); a.setPhoneVerified(true);
            users.save(a);
            log.info("Admin account created: {}", props.admin().email());
        }
        if (!props.seedDemo()) return;
        if (!users.existsByEmailIgnoreCase("customer@demo.local")) {
            User c = newUser("customer@demo.local", "+919111111111", "Demo Customer", Role.CUSTOMER);
            wallets.ensureWallet(c.getId());
            wallets.credit(c.getId(), new BigDecimal("1000.00"), "SIGNUP_BONUS", "seed", "Welcome bonus");
        }
        List<Long> skills = jdbc.sql("SELECT id FROM categories WHERE parent_id IS NOT NULL").query(Long.class).list();
        String[] names = {"Ravi Kumar", "Suresh Babu", "Imran Khan"};
        for (int i = 1; i <= 3; i++) {
            String email = "partner" + i + "@demo.local";
            if (users.existsByEmailIgnoreCase(email)) continue;
            User u = newUser(email, "+91922222222" + i, names[i - 1], Role.PARTNER);
            Partner p = partnerService.onboard(u.getId(), new PartnerService.ProfileRequest(names[i - 1], 1L, skills, "00112233445" + i, "HDFC0001234", names[i - 1]));
            PartnerDocument d = new PartnerDocument();
            d.setPartnerId(p.getId()); d.setDocType("GOVT_ID"); d.setDocNumber("DEMO-ID-" + i); d.setFileUrl("https://example.com/kyc/" + i + ".jpg"); d.setStatus("VERIFIED");
            documents.save(d);
            p.setStatus(PartnerStatus.APPROVED);
            partners.save(p);
        }
        log.info("Demo data ready (customer@demo.local, partner1..3@demo.local / {})", DEMO_PASSWORD);
    }

    private User newUser(String email, String phone, String name, Role role) {
        User u = new User();
        u.setEmail(email); u.setPhone(phone); u.setFullName(name); u.setRole(role); u.setPhoneVerified(true);
        u.setPasswordHash(encoder.encode(DEMO_PASSWORD));
        return users.save(u);
    }
}
