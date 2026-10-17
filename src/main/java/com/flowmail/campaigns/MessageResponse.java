package com.flowmail.campaigns;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String email,
        String status,
        int attempts,
        String lastError,
        Instant sentAt) {
}