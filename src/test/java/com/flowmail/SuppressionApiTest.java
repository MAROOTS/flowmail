package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmail.contacts.service.UnsubscribeTokenService;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SuppressionApiTest extends ApiTestSupport {

    @Autowired
    UnsubscribeTokenService tokens;

    @Test
    void manualSuppressionsFollowRoleAndRemovalRules() throws Exception {
        Account owner = register("Org A");
        Account manager = addMember(owner, "manager");
        Account viewer = addMember(owner, "viewer");
        String json = "{\"email\":\"Blocked@Example.com\"}";

        assertThat(send("POST", "/api/suppressions", viewer.token(), json).statusCode()).isEqualTo(403);

        HttpResponse<String> added = send("POST", "/api/suppressions", owner.token(), json);
        assertThat(added.statusCode()).isEqualTo(201);
        assertThat(added.body()).contains("blocked@example.com");
        String id = extract(added.body(), "id");

        assertThat(send("POST", "/api/suppressions", manager.token(), json).statusCode()).isEqualTo(409);
        assertThat(send("GET", "/api/suppressions", viewer.token(), null).body()).contains("blocked@example.com");

        assertThat(send("DELETE", "/api/suppressions/" + id, manager.token(), null).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/suppressions/" + id, owner.token(), null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/suppressions", owner.token(), null).body())
                .doesNotContain("blocked@example.com");

        String contactId = createContact(owner, "reader@example.com");
        String token = tokens.create(UUID.fromString(owner.tenantId()), UUID.fromString(contactId));
        assertThat(send("POST", "/unsubscribe/" + token, null, null).statusCode()).isEqualTo(200);

        String unsubscribeEntryId = extract(send("GET", "/api/suppressions", owner.token(), null).body(), "id");
        HttpResponse<String> refused = send("DELETE", "/api/suppressions/" + unsubscribeEntryId, owner.token(), null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("suppression_not_removable");
    }
}