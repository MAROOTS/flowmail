package com.flowmail.auth.web;

import com.flowmail.tenancy.TenantPrincipal;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    @GetMapping("/api/me")
    Map<String, Object> me(@AuthenticationPrincipal TenantPrincipal principal) {
        return Map.of(
                "userId", principal.userId().toString(),
                "tenantId", principal.tenantId().toString(),
                "role", principal.role());
    }
}