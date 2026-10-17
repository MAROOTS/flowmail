package com.flowmail.campaigns.web;

import com.flowmail.campaigns.UsageResponse;
import com.flowmail.campaigns.service.UsageService;
import com.flowmail.tenancy.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    @GetMapping("/api/usage")
    UsageResponse usage(@AuthenticationPrincipal TenantPrincipal principal) {
        return usageService.current(principal.tenantId());
    }
}