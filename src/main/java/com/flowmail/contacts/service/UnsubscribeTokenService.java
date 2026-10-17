package com.flowmail.contacts.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Stateless unsubscribe tokens: base64url(tenantId:contactId) + "." + base64url(HMAC-SHA256).
 * No expiry, because unsubscribe links in old emails must keep working.
 */
@Service
public class UnsubscribeTokenService {

    public record TokenData(UUID tenantId, UUID contactId) {
    }

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public UnsubscribeTokenService(@Value("${flowmail.unsubscribe.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("flowmail.unsubscribe.secret must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public String create(UUID tenantId, UUID contactId) {
        String payload = tenantId + ":" + contactId;
        return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + ENCODER.encodeToString(hmac(payload));
    }

    public Optional<TokenData> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        try {
            String payload = new String(DECODER.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(payload), signature)) {
                return Optional.empty();
            }
            String[] parts = payload.split(":");
            if (parts.length != 2) {
                return Optional.empty();
            }
            return Optional.of(new TokenData(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}