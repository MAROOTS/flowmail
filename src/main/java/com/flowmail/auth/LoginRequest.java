package com.flowmail.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String password,
        @Size(max = 100) String tenantSlug) {
}