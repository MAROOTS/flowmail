package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmail.contacts.service.UnsubscribeTokenService;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnsubscribeTest extends ApiTestSupport {

    @Autowired
    UnsubscribeTokenService tokens;

    @Test
    void unsubscribeLinkSuppressesContactAndSurvivesReimport() throws Exception {
        Account a = register("Org A");
        String contactId = createContact(a, "reader@example.com");
        String token = tokens.create(UUID.fromString(a.tenantId()), UUID.fromString(contactId));

        HttpResponse<String> page = send("GET", "/unsubscribe/" + token, null, null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<form");
        assertThat(send("GET", "/api/contacts/" + contactId, a.token(), null).body())
                .contains("\"status\":\"subscribed\"");

        assertThat(send("POST", "/unsubscribe/" + token, null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/contacts/" + contactId, a.token(), null).body())
                .contains("\"status\":\"unsubscribed\"");

        String suppressions = send("GET", "/api/suppressions", a.token(), null).body();
        assertThat(suppressions).contains("reader@example.com").contains("\"reason\":\"unsubscribe\"");

        assertThat(send("POST", "/unsubscribe/" + token, null, null).statusCode()).isEqualTo(200);

        HttpResponse<String> reimport = upload(a.token(), "again.csv", "email,first_name\nreader@example.com,Reader\n");
        assertThat(reimport.statusCode()).isEqualTo(201);
        assertThat(extractInt(reimport.body(), "suppressed")).isEqualTo(1);
        assertThat(extractInt(reimport.body(), "created")).isEqualTo(0);
        assertThat(extractInt(reimport.body(), "updated")).isEqualTo(0);
        assertThat(send("GET", "/api/contacts/" + contactId, a.token(), null).body())
                .contains("\"status\":\"unsubscribed\"");
    }

    @Test
    void invalidTamperedAndCrossTenantTokensAreRejected() throws Exception {
        Account a = register("Org A");
        Account b = register("Org B");
        String contactA = createContact(a, "a@example.com");
        String contactB = createContact(b, "b@example.com");

        assertThat(send("GET", "/unsubscribe/garbage", null, null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/unsubscribe/garbage", null, null).statusCode()).isEqualTo(404);

        String valid = tokens.create(UUID.fromString(a.tenantId()), UUID.fromString(contactA));
        String tampered = "x" + valid.substring(1);
        assertThat(send("POST", "/unsubscribe/" + tampered, null, null).statusCode()).isEqualTo(404);

        String crossTenant = tokens.create(UUID.fromString(a.tenantId()), UUID.fromString(contactB));
        assertThat(send("POST", "/unsubscribe/" + crossTenant, null, null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/contacts/" + contactB, b.token(), null).body())
                .contains("\"status\":\"subscribed\"");
    }
}