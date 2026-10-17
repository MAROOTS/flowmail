package com.flowmail.contacts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ImportJobResponse(
        UUID id,
        String filename,
        String status,
        int totalRows,
        int created,
        int updated,
        int suppressed,
        int failed,
        boolean errorsTruncated,
        Instant createdAt,
        List<RowError> errors) {

    public record RowError(int row, String email, String message) {
    }

    public ImportJobResponse withErrors(List<RowError> newErrors) {
        return new ImportJobResponse(id, filename, status, totalRows, created, updated,
                suppressed, failed, errorsTruncated, createdAt, newErrors);
    }
}