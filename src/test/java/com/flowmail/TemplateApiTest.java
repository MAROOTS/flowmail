package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TemplateApiTest extends ApiTestSupport {

    @Test
    void createValidateUpdateAndPreview() throws Exception {
        Account a = register("Org A");

        HttpResponse<String> created = send("POST", "/api/templates", a.token(),
                templateJson("Welcome", "Hi {{firstName}}", GOOD_HTML));
        assertThat(created.statusCode()).isEqualTo(201);
        String id = extract(created.body(), "id");

        assertThat(send("POST", "/api/templates", a.token(),
                templateJson("Welcome", "Hi {{firstName}}", GOOD_HTML)).statusCode()).isEqualTo(409);

        HttpResponse<String> unknown = send("POST", "/api/templates", a.token(),
                templateJson("Bad", "Hi {{nickname}}", GOOD_HTML));
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("unknown_variable").contains("nickname");

        HttpResponse<String> updated = send("PUT", "/api/templates/" + id, a.token(),
                templateJson("Welcome", "Hello {{firstName}}", GOOD_HTML));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("Hello {{firstName}}");

        HttpResponse<String> preview = send("GET", "/api/templates/" + id + "/preview", a.token(), null);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).contains("Hello Alex").doesNotContain("{{firstName}}");
    }

    @Test
    void templatesAreTenantScopedAndRoleProtected() throws Exception {
        Account a = register("Org A");
        Account b = register("Org B");
        Account viewer = addMember(a, "viewer");
        String id = createTemplate(a, "Welcome", GOOD_HTML);

        assertThat(send("GET", "/api/templates/" + id, b.token(), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/templates/" + id + "/preview", b.token(), null).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/api/templates/" + id, b.token(),
                templateJson("Welcome", "Hi", GOOD_HTML)).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/templates", b.token(), null).body()).doesNotContain("Welcome");

        assertThat(send("GET", "/api/templates/" + id, viewer.token(), null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/templates", viewer.token(),
                templateJson("Other", "Hi", GOOD_HTML)).statusCode()).isEqualTo(403);
    }
}