package com.flowmail.contacts.service;

import com.flowmail.common.ApiException;
import com.flowmail.contacts.ImportJobResponse;
import com.flowmail.contacts.ImportJobResponse.RowError;
import com.flowmail.tenancy.TenantTx;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContactImportService {

    public static final int MAX_ROWS = 5000;
    private static final int MAX_STORED_ERRORS = 1000;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    // Inserts unless the address is suppressed; updates names (never status) if the contact exists.
    // (xmax = 0) is a well-known Postgres idiom for "this row was inserted, not updated".
    private static final String UPSERT = """
            insert into contacts (tenant_id, email, first_name, last_name)
            select cast(:tenant as uuid), :email, cast(:first as text), cast(:last as text)
            where not exists (
                select 1 from suppression_entries s
                where s.tenant_id = cast(:tenant as uuid) and s.email = :email)
            on conflict (tenant_id, email) do update
                set first_name = coalesce(excluded.first_name, contacts.first_name),
                    last_name  = coalesce(excluded.last_name, contacts.last_name)
            returning (xmax = 0) as inserted
            """;

    private final JdbcClient jdbc;
    private final TenantTx tenantTx;

    private record ParsedRow(int row, String email, String firstName, String lastName) {
    }

    private record ParsedCsv(int totalRows, List<ParsedRow> rows, List<RowError> errors) {
    }

    public ImportJobResponse importCsv(UUID tenantId, UUID userId, String filename, byte[] content) {
        ParsedCsv parsed = parse(content);
        return tenantTx.call(tenantId, () -> persist(tenantId, userId, filename, parsed));
    }

    public ImportJobResponse get(UUID tenantId, UUID jobId) {
        return tenantTx.call(tenantId, () -> {
            ImportJobResponse job = jdbc.sql("""
                    select id, filename, status, total_rows, created_count, updated_count,
                           suppressed_count, failed_count, errors_truncated, created_at
                    from import_jobs
                    where tenant_id = :tenant and id = :id
                    """)
                    .param("tenant", tenantId)
                    .param("id", jobId)
                    .query((rs, i) -> new ImportJobResponse(
                            rs.getObject("id", UUID.class),
                            rs.getString("filename"),
                            rs.getString("status"),
                            rs.getInt("total_rows"),
                            rs.getInt("created_count"),
                            rs.getInt("updated_count"),
                            rs.getInt("suppressed_count"),
                            rs.getInt("failed_count"),
                            rs.getBoolean("errors_truncated"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            List.of()))
                    .optional()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                            "import_not_found", "Import not found"));

            List<RowError> errors = jdbc.sql("""
                    select row_num, email, message
                    from import_row_errors
                    where tenant_id = :tenant and import_id = :id
                    order by row_num
                    """)
                    .param("tenant", tenantId)
                    .param("id", jobId)
                    .query((rs, i) -> new RowError(
                            rs.getInt("row_num"),
                            rs.getString("email"),
                            rs.getString("message")))
                    .list();

            return job.withErrors(errors);
        });
    }

    private ParsedCsv parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_file", "The file is empty");
        }

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();

        List<ParsedRow> rows = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        Map<String, Integer> firstSeen = new HashMap<>();
        int total = 0;

        try (CSVParser parser = format.parse(new StringReader(text))) {
            Map<String, Integer> headerMap = parser.getHeaderMap();
            int emailCol = -1;
            int firstCol = -1;
            int lastCol = -1;
            if (headerMap != null) {
                for (Map.Entry<String, Integer> header : headerMap.entrySet()) {
                    String key = header.getKey() == null ? ""
                            : header.getKey().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
                    switch (key) {
                        case "email", "emailaddress" -> emailCol = header.getValue();
                        case "firstname" -> firstCol = header.getValue();
                        case "lastname" -> lastCol = header.getValue();
                        default -> {
                        }
                    }
                }
            }
            if (emailCol < 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "missing_email_column",
                        "The first row must be a header containing an 'email' column");
            }

            for (CSVRecord record : parser) {
                total++;
                if (total > MAX_ROWS) {
                    throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too_many_rows",
                            "A single import is limited to " + MAX_ROWS + " rows");
                }
                int row = total;
                String email = cell(record, emailCol);
                String firstName = cell(record, firstCol);
                String lastName = cell(record, lastCol);

                if (email == null) {
                    errors.add(new RowError(row, null, "Missing email"));
                    continue;
                }
                email = email.toLowerCase(Locale.ROOT);
                if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches()) {
                    errors.add(new RowError(row, truncate(email, MAX_EMAIL_LENGTH), "Invalid email address"));
                    continue;
                }
                if ((firstName != null && firstName.length() > MAX_NAME_LENGTH)
                        || (lastName != null && lastName.length() > MAX_NAME_LENGTH)) {
                    errors.add(new RowError(row, email, "Name is longer than " + MAX_NAME_LENGTH + " characters"));
                    continue;
                }
                Integer earlier = firstSeen.putIfAbsent(email, row);
                if (earlier != null) {
                    errors.add(new RowError(row, email, "Duplicate of row " + earlier));
                    continue;
                }
                rows.add(new ParsedRow(row, email, firstName, lastName));
            }
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_csv",
                    "The file could not be parsed as CSV");
        }

        return new ParsedCsv(total, rows, errors);
    }

    private ImportJobResponse persist(UUID tenantId, UUID userId, String filename, ParsedCsv parsed) {
        int created = 0;
        int updated = 0;
        int suppressed = 0;

        for (ParsedRow row : parsed.rows()) {
            Optional<Boolean> inserted = jdbc.sql(UPSERT)
                    .param("tenant", tenantId)
                    .param("email", row.email())
                    .param("first", row.firstName())
                    .param("last", row.lastName())
                    .query(Boolean.class)
                    .optional();
            if (inserted.isEmpty()) {
                suppressed++;
            } else if (inserted.get()) {
                created++;
            } else {
                updated++;
            }
        }

        int failed = parsed.errors().size();
        boolean truncated = failed > MAX_STORED_ERRORS;
        List<RowError> stored = truncated ? parsed.errors().subList(0, MAX_STORED_ERRORS) : parsed.errors();
        UUID jobId = UUID.randomUUID();

        OffsetDateTime createdAt = jdbc.sql("""
                insert into import_jobs (id, tenant_id, created_by, filename, status, total_rows,
                                         created_count, updated_count, suppressed_count, failed_count,
                                         errors_truncated)
                values (:id, :tenant, :user, :filename, 'completed', :total,
                        :created, :updated, :suppressed, :failed, :truncated)
                returning created_at
                """)
                .param("id", jobId)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("filename", filename)
                .param("total", parsed.totalRows())
                .param("created", created)
                .param("updated", updated)
                .param("suppressed", suppressed)
                .param("failed", failed)
                .param("truncated", truncated)
                .query((rs, i) -> rs.getObject("created_at", OffsetDateTime.class))
                .single();

        for (RowError error : stored) {
            jdbc.sql("""
                    insert into import_row_errors (import_id, tenant_id, row_num, email, message)
                    values (:job, :tenant, :row, :email, :message)
                    """)
                    .param("job", jobId)
                    .param("tenant", tenantId)
                    .param("row", error.row())
                    .param("email", error.email())
                    .param("message", error.message())
                    .update();
        }

        return new ImportJobResponse(jobId, filename, "completed", parsed.totalRows(),
                created, updated, suppressed, failed, truncated, createdAt.toInstant(), stored);
    }

    private static String cell(CSVRecord record, int column) {
        if (column < 0 || column >= record.size()) {
            return null;
        }
        String value = record.get(column);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}