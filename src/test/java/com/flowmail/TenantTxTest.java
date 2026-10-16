package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowmail.tenancy.TenantTx;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
class TenantTxTest extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantTx tenantTx;

    @Test
    void queriesInsideTenantTxSeeOnlyThatTenantsRows() {
        UUID a = newTenant();
        UUID b = newTenant();
        String emailA = "a-" + UUID.randomUUID() + "@example.com";
        String emailB = "b-" + UUID.randomUUID() + "@example.com";
        insertContact(a, emailA);
        insertContact(b, emailB);

        assertThat(emailsVisibleIn(a)).containsExactly(emailA);
        assertThat(emailsVisibleIn(b)).containsExactly(emailB);

        List<String> outside = jdbc.sql("select email from contacts").query(String.class).list();
        assertThat(outside).isEmpty();
    }

    @Test
    void writeIntoAnotherTenantIsRejected() {
        UUID a = newTenant();
        UUID b = newTenant();

        assertThatThrownBy(() -> tenantTx.run(a, () ->
                jdbc.sql("insert into contacts (tenant_id, email) values (:t, :e)")
                        .param("t", b)
                        .param("e", "intruder-" + UUID.randomUUID() + "@example.com")
                        .update()))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID newTenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into tenants (id, name, slug) values (:id, :name, :slug)")
                .param("id", id)
                .param("name", "Tenant " + id)
                .param("slug", "t-" + id)
                .update();
        return id;
    }

    private void insertContact(UUID tenant, String email) {
        tenantTx.run(tenant, () ->
                jdbc.sql("insert into contacts (tenant_id, email) values (:t, :e)")
                        .param("t", tenant)
                        .param("e", email)
                        .update());
    }

    private List<String> emailsVisibleIn(UUID tenant) {
        return tenantTx.call(tenant, () ->
                jdbc.sql("select email from contacts order by email").query(String.class).list());
    }
}