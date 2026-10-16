package com.flowmail.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder,
                      @Value("${flowmail.jwt.issuer}") String issuer,
                      @Value("${flowmail.jwt.ttl-minutes}") long ttlMinutes) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(UUID userId, UUID tenantId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}