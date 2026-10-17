package com.flowmail.campaigns;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateCampaignRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull UUID templateId,
        @NotBlank @Size(max = 100) String fromName,
        @NotBlank @Email @Size(max = 254) String fromEmail) {
}