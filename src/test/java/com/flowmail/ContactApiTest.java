package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContactApiTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    record Account(String email, String token, String tenantId, String slug) {
    }

    @Test
    void tenantsCannotSeeEachOthersContacts() throws Exception {
        Account a = register("Org A");
        Account b = register("Org B");

        HttpResponse<String> created = send("POST", "/api/contacts", a.token(), contactJson("alice@example.com"));
        assertThat(created.statusCode()).isEqualTo(201);
        String contactId = extract(created.body(), "id");

        assertThat(send("GET", "/api/contacts", a.token(), null).body()).contains("alice@example.com");

        HttpResponse<String> listB = send("GET", "/api/contacts", b.token(), null);
        assertThat(listB.statusCode()).isEqualTo(200);
        assertThat(listB.body()).doesNotContain("alice@example.com");

        assertThat(send("GET", "/api/contacts/" + contactId, a.token(), null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/contacts/" + contactId, b.token(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void sameEmailAllowedAcrossTenantsButNotWithinOne() throws Exception {
        Account a = register("Org A");
        Account b = register("Org B");

        assertThat(send("POST", "/api/contacts", a.token(), contactJson("shared@example.com")).statusCode())
                .isEqualTo(201);
        assertThat(send("POST", "/api/contacts", b.token(), contactJson("shared@example.com")).statusCode())
                .isEqualTo(201);
        assertThat(send("POST", "/api/contacts", a.token(), contactJson("SHARED@example.com")).statusCode())
                .isEqualTo(409);
    }

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        Account owner = register("Org A");
        Account viewer = addMember(owner, "viewer");

        assertThat(send("GET", "/api/contacts", viewer.token(), null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/contacts", viewer.token(), contactJson("nope@example.com")).statusCode())
                .isEqualTo(403);
        assertThat(send("POST", "/api/contacts", owner.token(), contactJson("yes@example.com")).statusCode())
                .isEqualTo(201);
    }

    @Test
    void removedMemberLosesAccessImmediately() throws Exception {
        Account owner = register("Org A");
        Account viewer = addMember(owner, "viewer");
        assertThat(send("GET", "/api/contacts", viewer.token(), null).statusCode()).isEqualTo(200);

        removeMember(owner, viewer);

        assertThat(send("GET", "/api/contacts", viewer.token(), null).statusCode()).isEqualTo(401);
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        assertThat(send("GET", "/api/contacts", null, null).statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/contacts", null, contactJson("x@example.com")).statusCode()).isEqualTo(401);
    }

    private Account register(String organization) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> response = send("POST", "/api/auth/register", null, """
                {"organizationName":"%s","email":"%s","password":"%s","displayName":"Tester"}
                """.formatted(organization, email, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return new Account(email,
                extract(response.body(), "accessToken"),
                extract(response.body(), "tenantId"),
                extract(response.body(), "tenantSlug"));
    }

    private Account addMember(Account tenantOwner, String role) throws Exception {
        Account member = register("Member Home");
        try (Connection c = ownerConnection();
             PreparedStatement ps = c.prepareStatement("""
                     insert into memberships (tenant_id, user_id, role)
                     select ?::uuid, id, ?::text from users where lower(email) = ?
                     """)) {
            ps.setString(1, tenantOwner.tenantId());
            ps.setString(2, role);
            ps.setString(3, member.email());
            ps.executeUpdate();
        }
        HttpResponse<String> login = send("POST", "/api/auth/login", null, """
                {"email":"%s","password":"%s","tenantSlug":"%s"}
                """.formatted(member.email(), PASSWORD, tenantOwner.slug()));
        assertThat(login.statusCode()).isEqualTo(200);
        return new Account(member.email(), extract(login.body(), "accessToken"),
                tenantOwner.tenantId(), tenantOwner.slug());
    }

    private void removeMember(Account tenantOwner, Account member) throws Exception {
        try (Connection c = ownerConnection();
             PreparedStatement ps = c.prepareStatement("""
                     delete from memberships
                     where tenant_id = ?::uuid
                       and user_id = (select id from users where lower(email) = ?)
                     """)) {
            ps.setString(1, tenantOwner.tenantId());
            ps.setString(2, member.email());
            ps.executeUpdate();
        }
    }

    private Connection ownerConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private String contactJson(String email) {
        return """
                {"email":"%s","firstName":"Test","lastName":"Person"}
                """.formatted(email);
    }

    private HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (json != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("field " + field + " in " + json).isTrue();
        return m.group(1);
    }
}