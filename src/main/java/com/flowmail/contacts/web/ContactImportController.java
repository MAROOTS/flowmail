package com.flowmail.contacts.web;

import com.flowmail.common.ApiException;
import com.flowmail.contacts.ImportJobResponse;
import com.flowmail.contacts.service.ContactImportService;
import com.flowmail.tenancy.TenantPrincipal;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/contacts")
@RequiredArgsConstructor
public class ContactImportController {

    private final ContactImportService importService;

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ImportJobResponse importCsv(@AuthenticationPrincipal TenantPrincipal principal,
                                @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_file", "The file is empty");
        }
        return importService.importCsv(principal.tenantId(), principal.userId(),
                sanitize(file.getOriginalFilename()), file.getBytes());
    }

    @GetMapping("/imports/{id}")
    ImportJobResponse getImport(@AuthenticationPrincipal TenantPrincipal principal,
                                @PathVariable UUID id) {
        return importService.get(principal.tenantId(), id);
    }

    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload.csv";
        }
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        name = name.isBlank() ? "upload.csv" : name;
        return name.length() > 200 ? name.substring(0, 200) : name;
    }
}