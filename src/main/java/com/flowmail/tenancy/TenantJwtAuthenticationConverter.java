package com.flowmail.tenancy;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

/**
 * Runs after the JWT signature is verified. Re-checks against the database that the user
 * is still an active member of the tenant in the token, and takes the role from the database.
 */
@Component
@RequiredArgsConstructor
public class TenantJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JdbcClient jdbc;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        UUID tenantId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            tenantId = UUID.fromString(jwt.getClaimAsString("tenant_id"));
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("Malformed token claims");
        }

        Optional<String> role = jdbc.sql("""
                select m.role
                from memberships m
                join tenants t on t.id = m.tenant_id
                join users u on u.id = m.user_id
                where m.user_id = :userId
                  and m.tenant_id = :tenantId
                  and t.status = 'active'
                  and u.status = 'active'
                """)
                .param("userId", userId)
                .param("tenantId", tenantId)
                .query(String.class)
                .optional();

        String currentRole = role.orElseThrow(
                () -> new InvalidBearerTokenException("Membership is no longer valid"));

        TenantPrincipal principal = new TenantPrincipal(userId, tenantId, currentRole);
        return new TenantAuthentication(
                principal,
                jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + currentRole.toUpperCase(Locale.ROOT))));
    }
}