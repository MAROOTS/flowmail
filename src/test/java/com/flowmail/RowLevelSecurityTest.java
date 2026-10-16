package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class RowLevelSecurityTest extends PostgresIntegrationTest {

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @Test
    void appRoleSeesOnlyTheCurrentTenantsRows() throws Exception {
        seedAsOwner();

        try (Connection app = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "flowmail_app", "flowmail_app")) {

            assertThat(emailsAs(app, null)).isEmpty();
            assertThat(emailsAs(app, tenantA)).containsExactly("a@example.com");
            assertThat(emailsAs(app, tenantB)).containsExactly("b@example.com");

            // Same pooled connection, tenant context already cleared: must be empty, not an error.
            assertThat(emailsAs(app, null)).isEmpty();
        }
    }

    @Test
    void appRoleCannotWriteIntoAnotherTenant() throws Exception {
        seedAsOwner();

        try (Connection app = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "flowmail_app", "flowmail_app")) {
            app.setAutoCommit(false);
            try {
                setTenant(app, tenantA);
                assertThatThrownBy(() -> {
                    try (PreparedStatement ps = app.prepareStatement(
                            "insert into contacts (tenant_id, email) values (?, ?)")) {
                        ps.setObject(1, tenantB);
                        ps.setString(2, "intruder@example.com");
                        ps.executeUpdate();
                    }
                }).isInstanceOf(SQLException.class);
            } finally {
                app.rollback();
            }
        }
    }

    private void seedAsOwner() throws SQLException {
        try (Connection owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            insertTenant(owner, tenantA, "tenant-a-" + tenantA);
            insertTenant(owner, tenantB, "tenant-b-" + tenantB);
            insertContact(owner, tenantA, "a@example.com");
            insertContact(owner, tenantB, "b@example.com");
        }
    }

    private void insertTenant(Connection c, UUID id, String slug) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into tenants (id, name, slug) values (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, slug);
            ps.setString(3, slug);
            ps.executeUpdate();
        }
    }

    private void insertContact(Connection c, UUID tenant, String email) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into contacts (tenant_id, email) values (?, ?)")) {
            ps.setObject(1, tenant);
            ps.setString(2, email);
            ps.executeUpdate();
        }
    }

    private void setTenant(Connection c, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenant.toString());
            ps.execute();
        }
    }

    private List<String> emailsAs(Connection c, UUID tenant) throws SQLException {
        c.setAutoCommit(false);
        List<String> emails = new ArrayList<>();
        try {
            if (tenant != null) {
                setTenant(c, tenant);
            }
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("select email from contacts order by email")) {
                while (rs.next()) {
                    emails.add(rs.getString(1));
                }
            }
        } finally {
            c.rollback();
        }
        return emails;
    }
}
