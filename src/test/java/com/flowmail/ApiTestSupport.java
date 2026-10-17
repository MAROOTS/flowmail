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
import org.springframework.beans.factory.annotation.Value;

public abstract class ApiTestSupport extends PostgresIntegrationTest {

    protected static final String PASSWORD = "correct-horse-battery";

    @Value("${local.server.port}")
    protected int port;

    private final HttpClient http = HttpClient.newHttpClient();

    protected record Account(String email, String token, String tenantId, String slug) {
    }

    protected Account register(String organization) throws Exception {
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

    protected Account addMember(Account owner, String role) throws Exception {
        Account member = register("Member Home");
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = c.prepareStatement("""
                     insert into memberships (tenant_id, user_id, role)
                     select ?::uuid, id, ?::text from users where lower(email) = ?
                     """)) {
            ps.setString(1, owner.tenantId());
            ps.setString(2, role);
            ps.setString(3, member.email());
            ps.executeUpdate();
        }
        HttpResponse<String> login = send("POST", "/api/auth/login", null, """
                {"email":"%s","password":"%s","tenantSlug":"%s"}
                """.formatted(member.email(), PASSWORD, owner.slug()));
        assertThat(login.statusCode()).isEqualTo(200);
        return new Account(member.email(), extract(login.body(), "accessToken"),
                owner.tenantId(), owner.slug());
    }

    protected HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
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

    protected HttpResponse<String> upload(String token, String filename, String csv) throws Exception {
        String boundary = "----flowmail" + UUID.randomUUID();
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + csv + "\r\n--" + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/contacts/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    protected String createContact(Account account, String email) throws Exception {
        HttpResponse<String> response = send("POST", "/api/contacts", account.token(),
                "{\"email\":\"" + email + "\",\"firstName\":\"Test\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return extract(response.body(), "id");
    }

    protected String extract(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("field " + field + " in " + json).isTrue();
        return m.group(1);
    }

    protected int extractInt(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        assertThat(m.find()).as("number " + field + " in " + json).isTrue();
        return Integer.parseInt(m.group(1));
    }
}