package com.flowmail.tenancy;

import java.util.UUID;

public record TenantPrincipal(UUID userId, UUID tenantId, String role) {
}