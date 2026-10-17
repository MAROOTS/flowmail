package com.flowmail.campaigns.web;

import com.flowmail.campaigns.CampaignResponse;
import com.flowmail.campaigns.CreateCampaignRequest;
import com.flowmail.campaigns.MessageResponse;
import com.flowmail.campaigns.service.CampaignService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CampaignResponse create(@AuthenticationPrincipal TenantPrincipal principal,
                            @Valid @RequestBody CreateCampaignRequest request) {
        return campaignService.create(principal.tenantId(), principal.userId(), request);
    }

    @GetMapping
    List<CampaignResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        return campaignService.list(principal.tenantId(), limit, offset);
    }

    @GetMapping("/{id}")
    CampaignResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return campaignService.get(principal.tenantId(), id);
    }

    @PostMapping("/{id}/launch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    CampaignResponse launch(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return campaignService.launch(principal.tenantId(), id);
    }

    @GetMapping("/{id}/messages")
    List<MessageResponse> messages(@AuthenticationPrincipal TenantPrincipal principal,
                                   @PathVariable UUID id,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset) {
        return campaignService.messages(principal.tenantId(), id, limit, offset);
    }
}