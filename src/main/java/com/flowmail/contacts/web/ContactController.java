package com.flowmail.contacts.web;

import com.flowmail.contacts.ContactResponse;
import com.flowmail.contacts.CreateContactRequest;
import com.flowmail.contacts.service.ContactService;
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
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ContactResponse create(@AuthenticationPrincipal TenantPrincipal principal,
                           @Valid @RequestBody CreateContactRequest request) {
        return contactService.create(principal.tenantId(), request);
    }

    @GetMapping
    List<ContactResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
                               @RequestParam(defaultValue = "50") int limit,
                               @RequestParam(defaultValue = "0") int offset) {
        return contactService.list(principal.tenantId(), limit, offset);
    }

    @GetMapping("/{id}")
    ContactResponse get(@AuthenticationPrincipal TenantPrincipal principal,
                        @PathVariable UUID id) {
        return contactService.get(principal.tenantId(), id);
    }
}