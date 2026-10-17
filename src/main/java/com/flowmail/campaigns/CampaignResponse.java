package com.flowmail.campaigns;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CampaignResponse(
        UUID id,
        String name,
        UUID templateId,
        String fromName,
        String fromEmail,
        String status,
        int recipientCount,
        Instant launchedAt,
        Instant createdAt,
        Map<String, Integer> messageCounts) {

    public CampaignResponse withCounts(Map<String, Integer> counts) {
        return new CampaignResponse(id, name, templateId, fromName, fromEmail, status,
                recipientCount, launchedAt, createdAt, counts);
    }
}