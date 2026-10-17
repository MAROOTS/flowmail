package com.flowmail.campaigns;

import java.time.Instant;
import java.util.UUID;

public record TemplateSummary(UUID id, String name, String subject, Instant updatedAt) {
}