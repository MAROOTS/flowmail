package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void registerLoginAndUseToken() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery";

        HttpResponse<String> registered = post("/api/auth/register", registerJson(email, password));
        assertThat(registered.statusCode()).isEqualTo(201);

        assertThat(post("/api/auth/register", registerJson(email, password)).statusCode()).isEqualTo(409);
        assertThat(post("/api/auth/register", registerJson("weak-" + email, "short")).statusCode()).isEqualTo(400);

        assertThat(post("/api/auth/login", loginJson(email, "wrong-password")).statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/login", loginJson("nobody-" + email, password)).statusCode()).isEqualTo(401);

        HttpResponse<String> login = post("/api/auth/login", loginJson(email, password));
        assertThat(login.statusCode()).isEqualTo(200);
        String token = extract(login.body(), "accessToken");

        assertThat(get("/api/me", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/me", "not-a-token").statusCode()).isEqualTo(401);
        assertThat(get("/api/me", token.substring(0, token.length() - 3) + "abc").statusCode()).isEqualTo(401);

        HttpResponse<String> me = get("/api/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"role\":\"owner\"");
        assertThat(me.body()).contains(extract(login.body(), "tenantId"));
    }

    private String registerJson(String email, String password) {
        return """
                {"organizationName":"Acme Test","email":"%s","password":"%s","displayName":"Test Owner"}
                """.formatted(email, password);
    }

    private String loginJson(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
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