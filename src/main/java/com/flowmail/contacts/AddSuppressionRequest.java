package com.flowmail.contacts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddSuppressionRequest(
        @NotBlank @Email @Size(max = 254) String email) {
}