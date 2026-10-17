package com.flowmail.campaigns;

import java.time.Instant;
import java.util.UUID;

public record TemplateResponse(
        UUID id,
        String name,
        String subject,
        String htmlBody,
        String textBody,
        Instant createdAt,
        Instant updatedAt) {
}