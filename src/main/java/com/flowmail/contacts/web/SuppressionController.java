package com.flowmail.contacts.web;

import com.flowmail.contacts.AddSuppressionRequest;
import com.flowmail.contacts.SuppressionResponse;
import com.flowmail.contacts.service.SuppressionService;
import com.flowmail.tenancy.TenantPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/suppressions")
@RequiredArgsConstructor
public class SuppressionController {

    private final SuppressionService suppressionService;

    @GetMapping
    List<SuppressionResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset) {
        return suppressionService.list(principal.tenantId(), limit, offset);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SuppressionResponse add(@AuthenticationPrincipal TenantPrincipal principal,
                            @Valid @RequestBody AddSuppressionRequest request) {
        return suppressionService.addManual(principal.tenantId(), request.email());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        suppressionService.removeManual(principal.tenantId(), id);
    }
}