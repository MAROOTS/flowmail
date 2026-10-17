package com.flowmail.campaigns.web;

import com.flowmail.campaigns.TemplatePreview;
import com.flowmail.campaigns.TemplateRequest;
import com.flowmail.campaigns.TemplateResponse;
import com.flowmail.campaigns.TemplateSummary;
import com.flowmail.campaigns.service.TemplateService;
import com.flowmail.tenancy.TenantPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TemplateResponse create(@AuthenticationPrincipal TenantPrincipal principal,
                            @Valid @RequestBody TemplateRequest request) {
        return templateService.create(principal.tenantId(), principal.userId(), request);
    }

    @GetMapping
    List<TemplateSummary> list(@AuthenticationPrincipal TenantPrincipal principal,
                               @RequestParam(defaultValue = "50") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        return templateService.list(principal.tenantId(), limit, offset);
    }

    @GetMapping("/{id}")
    TemplateResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return templateService.get(principal.tenantId(), id);
    }

    @PutMapping("/{id}")
    TemplateResponse update(@AuthenticationPrincipal TenantPrincipal principal,
                            @PathVariable UUID id,
                            @Valid @RequestBody TemplateRequest request) {
        return templateService.update(principal.tenantId(), id, request);
    }

    @GetMapping("/{id}/preview")
    TemplatePreview preview(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return templateService.preview(principal.tenantId(), id);
    }
}