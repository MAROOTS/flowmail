package com.flowmail.auth.service;

import com.flowmail.auth.AuthResponse;
import com.flowmail.auth.LoginRequest;
import com.flowmail.auth.RegisterRequest;
import com.flowmail.common.ApiException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyHash;

    public AuthService(JdbcClient jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        // Spends the same hashing time when the email does not exist.
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String orgName = request.organizationName().trim();
        String slug = slugify(orgName) + "-" + randomSuffix();

        try {
            jdbc.sql("""
                    insert into users (id, email, password_hash, display_name)
                    values (:id, :email, :hash, :name)
                    """)
                    .param("id", userId)
                    .param("email", email)
                    .param("hash", passwordEncoder.encode(request.password()))
                    .param("name", request.displayName().trim())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken",
                    "An account with this email already exists");
        }

        jdbc.sql("insert into tenants (id, name, slug) values (:id, :name, :slug)")
                .param("id", tenantId)
                .param("name", orgName)
                .param("slug", slug)
                .update();

        jdbc.sql("insert into memberships (tenant_id, user_id, role) values (:t, :u, 'owner')")
                .param("t", tenantId)
                .param("u", userId)
                .update();

        return tokenFor(userId, tenantId, slug, "owner");
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());

        var user = jdbc.sql("select id, password_hash, status from users where lower(email) = :email")
                .param("email", email)
                .query((rs, i) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("password_hash"),
                        rs.getString("status")))
                .optional();

        String hash = user.map(UserRow::passwordHash).orElse(dummyHash);
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !passwordOk || !"active".equals(user.get().status())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid email or password");
        }
        UUID userId = user.get().id();

        List<MembershipRow> memberships = jdbc.sql("""
                select m.tenant_id, t.slug, m.role
                from memberships m
                join tenants t on t.id = m.tenant_id
                where m.user_id = :userId and t.status = 'active'
                order by t.slug
                """)
                .param("userId", userId)
                .query((rs, i) -> new MembershipRow(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("role")))
                .list();

        if (memberships.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "no_active_membership",
                    "This account does not belong to an active organization");
        }

        MembershipRow chosen;
        String requestedSlug = request.tenantSlug();
        if (requestedSlug != null && !requestedSlug.isBlank()) {
            chosen = memberships.stream()
                    .filter(m -> m.slug().equals(requestedSlug.trim()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "tenant_not_available",
                            "No access to the requested organization"));
        } else if (memberships.size() == 1) {
            chosen = memberships.get(0);
        } else {
            String slugs = String.join(", ", memberships.stream().map(MembershipRow::slug).toList());
            throw new ApiException(HttpStatus.CONFLICT, "tenant_selection_required",
                    "Specify tenantSlug. Available: " + slugs);
        }

        return tokenFor(userId, chosen.tenantId(), chosen.slug(), chosen.role());
    }

    private AuthResponse tokenFor(UUID userId, UUID tenantId, String slug, String role) {
        String token = jwtService.issue(userId, tenantId, role);
        return new AuthResponse(token, "Bearer", jwtService.ttlSeconds(), tenantId, slug, role);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String slugify(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 40) {
            s = s.substring(0, 40).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "org" : s;
    }

    private static String randomSuffix() {
        return String.format("%06x", RANDOM.nextInt(0x1000000));
    }

    private record UserRow(UUID id, String passwordHash, String status) {
    }

    private record MembershipRow(UUID tenantId, String slug, String role) {
    }
}