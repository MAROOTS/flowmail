package com.flowmail.contacts;

import java.time.Instant;
import java.util.UUID;

public record SuppressionResponse(
        UUID id,
        String email,
        String reason,
        String source,
        Instant createdAt) {
}