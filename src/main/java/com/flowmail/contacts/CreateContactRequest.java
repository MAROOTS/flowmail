package com.flowmail.contacts;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateContactRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 100) String firstName,
        @Size(max = 100) String lastName) {
}