package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContactImportTest extends ApiTestSupport {

    private static final String CSV = """
            Email,First Name,Last Name
            alice@example.com,Alice,Anderson
            bob@example.com,Bob,
            not-an-email,Carol,Clark
            "dave@example.com","Dave, Jr.",Dunn
            alice@example.com,Alice,Again
            blocked@example.com,Eve,Evans
            """;

    @Test
    void importCreatesUpdatesSkipsSuppressedAndReportsRowErrors() throws Exception {
        Account a = register("Org A");
        Account b = register("Org B");

        assertThat(send("POST", "/api/suppressions", a.token(), "{\"email\":\"Blocked@Example.com\"}")
                .statusCode()).isEqualTo(201);

        HttpResponse<String> first = upload(a.token(), "people.csv", CSV);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(extractInt(first.body(), "totalRows")).isEqualTo(6);
        assertThat(extractInt(first.body(), "created")).isEqualTo(3);
        assertThat(extractInt(first.body(), "updated")).isEqualTo(0);
        assertThat(extractInt(first.body(), "suppressed")).isEqualTo(1);
        assertThat(extractInt(first.body(), "failed")).isEqualTo(2);
        assertThat(first.body()).contains("Invalid email address").contains("Duplicate of row 1");

        String contacts = send("GET", "/api/contacts?limit=100", a.token(), null).body();
        assertThat(contacts).contains("alice@example.com").contains("Dave, Jr.");
        assertThat(contacts).doesNotContain("blocked@example.com").doesNotContain("not-an-email");

        String secondCsv = "email,first_name,last_name\nALICE@example.com,Alicia,\n";
        HttpResponse<String> second = upload(a.token(), "update.csv", secondCsv);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(extractInt(second.body(), "created")).isEqualTo(0);
        assertThat(extractInt(second.body(), "updated")).isEqualTo(1);

        String after = send("GET", "/api/contacts?limit=100", a.token(), null).body();
        assertThat(after).contains("Alicia").contains("Anderson");

        String jobId = extract(first.body(), "id");
        HttpResponse<String> job = send("GET", "/api/contacts/imports/" + jobId, a.token(), null);
        assertThat(job.statusCode()).isEqualTo(200);
        assertThat(job.body()).contains("Invalid email address");
        assertThat(send("GET", "/api/contacts/imports/" + jobId, b.token(), null).statusCode()).isEqualTo(404);

        assertThat(send("GET", "/api/contacts?limit=100", b.token(), null).body()).doesNotContain("alice@example.com");
    }

    @Test
    void rejectsBadFilesAndUnauthorizedUploaders() throws Exception {
        Account owner = register("Org A");
        Account viewer = addMember(owner, "viewer");

        HttpResponse<String> noEmailColumn = upload(owner.token(), "bad.csv", "name,phone\nAlice,123\n");
        assertThat(noEmailColumn.statusCode()).isEqualTo(400);
        assertThat(noEmailColumn.body()).contains("missing_email_column");

        assertThat(upload(owner.token(), "empty.csv", "").statusCode()).isEqualTo(400);

        StringBuilder tooMany = new StringBuilder("email\n");
        for (int i = 0; i < 5001; i++) {
            tooMany.append("user").append(i).append("@example.com\n");
        }
        HttpResponse<String> large = upload(owner.token(), "large.csv", tooMany.toString());
        assertThat(large.statusCode()).isEqualTo(413);
        assertThat(large.body()).contains("too_many_rows");

        assertThat(upload(viewer.token(), "people.csv", "email\nx@example.com\n").statusCode()).isEqualTo(403);
    }
}