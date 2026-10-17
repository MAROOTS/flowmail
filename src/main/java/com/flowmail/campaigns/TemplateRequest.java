package com.flowmail.campaigns;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemplateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 200000) String htmlBody,
        @Size(max = 200000) String textBody) {
}