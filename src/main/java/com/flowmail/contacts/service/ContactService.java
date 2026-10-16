package com.flowmail.contacts.service;

import com.flowmail.common.ApiException;
import com.flowmail.contacts.ContactResponse;
import com.flowmail.contacts.CreateContactRequest;
import com.flowmail.tenancy.TenantTx;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ContactService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final RowMapper<ContactResponse> MAPPER = (rs, i) -> new ContactResponse(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final TenantTx tenantTx;

    public ContactResponse create(UUID tenantId, CreateContactRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String firstName = blankToNull(request.firstName());
        String lastName = blankToNull(request.lastName());

        return tenantTx.call(tenantId, () -> {
            try {
                return jdbc.sql("""
                        insert into contacts (tenant_id, email, first_name, last_name)
                        values (:tenant, :email, :first, :last)
                        returning id, email, first_name, last_name, status, created_at
                        """)
                        .param("tenant", tenantId)
                        .param("email", email)
                        .param("first", firstName)
                        .param("last", lastName)
                        .query(MAPPER)
                        .single();
            } catch (DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "contact_exists",
                        "A contact with this email already exists");
            }
        });
    }

    public List<ContactResponse> list(UUID tenantId, int limit, int offset) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int start = Math.max(offset, 0);

        return tenantTx.call(tenantId, () -> jdbc.sql("""
                select id, email, first_name, last_name, status, created_at
                from contacts
                where tenant_id = :tenant
                order by created_at, id
                limit :limit offset :offset
                """)
                .param("tenant", tenantId)
                .param("limit", pageSize)
                .param("offset", start)
                .query(MAPPER)
                .list());
    }

    public ContactResponse get(UUID tenantId, UUID contactId) {
        return tenantTx.call(tenantId, () -> jdbc.sql("""
                select id, email, first_name, last_name, status, created_at
                from contacts
                where tenant_id = :tenant and id = :id
                """)
                        .param("tenant", tenantId)
                        .param("id", contactId)
                        .query(MAPPER)
                        .optional())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "contact_not_found", "Contact not found"));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}