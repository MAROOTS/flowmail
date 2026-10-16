package com.flowmail.contacts;

import java.time.Instant;
import java.util.UUID;

public record ContactResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String status,
        Instant createdAt) {
}