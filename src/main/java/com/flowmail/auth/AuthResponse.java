package com.flowmail.auth;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        UUID tenantId,
        String tenantSlug,
        String role) {
}