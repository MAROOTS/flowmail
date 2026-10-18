package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowmail.delivery.provider.MockEmailProvider;
import com.flowmail.delivery.worker.DeliveryWorker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeliveryWorkerTest extends ApiTestSupport {

    @Autowired
    DeliveryWorker worker;

    @Autowired
    MockEmailProvider mock;

    private record Launched(Account account, String campaignId, List<String> emails) {
    }

    @BeforeEach
    void cleanSlate() {
        drain();
        mock.reset();
    }

    @Test
    void sendsEveryQueuedMessageExactlyOnceAndCompletesCampaign() throws Exception {
        Launched l = launched(3);
        assertThat(campaignStatus(l)).isEqualTo("queued");

        drain();

        for (String email : l.emails()) {
            assertThat(messageStatus(l, email)).isEqualTo("sent");
            assertThat(mock.deliveriesTo(email)).isEqualTo(1);
        }
        assertThat(campaignStatus(l)).isEqualTo("completed");
        assertThat(send("GET", "/api/campaigns/" + l.campaignId(), l.account().token(), null).body())
                .contains("\"sent\":3");
        assertThat(attemptRows(l)).isEqualTo(3);
    }

    @Test
    void transientFailuresRetryThenSucceed() throws Exception {
        Launched l = launched(2);
        String flaky = l.emails().get(0);
        mock.failTransiently(flaky, 2);

        drain();

        assertThat(messageStatus(l, flaky)).isEqualTo("sent");
        assertThat(mock.deliveriesTo(flaky)).isEqualTo(1);
        assertThat(messageStatus(l, l.emails().get(1))).isEqualTo("sent");
        assertThat(attemptRows(l)).isEqualTo(4);
        assertThat(campaignStatus(l)).isEqualTo("completed");
    }

    @Test
    void exhaustedRetriesFailAndPermanentFailuresAreNotRetried() throws Exception {
        Launched l = launched(3);
        String alwaysFlaky = l.emails().get(0);
        String bounced = l.emails().get(1);
        String fine = l.emails().get(2);
        mock.failTransiently(alwaysFlaky, 99);
        mock.failPermanently(bounced);

        drain();

        assertThat(messageStatus(l, alwaysFlaky)).isEqualTo("failed");
        assertThat(messageStatus(l, bounced)).isEqualTo("failed");
        assertThat(messageStatus(l, fine)).isEqualTo("sent");
        assertThat(messagesBody(l)).contains("Gave up after 5 attempts");
        assertThat(attemptRows(l)).isEqualTo(7);
        assertThat(campaignStatus(l)).isEqualTo("completed");
    }

    @Test
    void uncertainResultsBecomeUnknownAndAreNotRetried() throws Exception {
        Launched l = launched(2);
        String uncertain = l.emails().get(0);
        mock.failUncertain(uncertain);

        drain();

        assertThat(messageStatus(l, uncertain)).isEqualTo("unknown");
        assertThat(mock.deliveriesTo(uncertain)).isZero();
        assertThat(messageStatus(l, l.emails().get(1))).isEqualTo("sent");
        assertThat(attemptRows(l)).isEqualTo(2);
        assertThat(campaignStatus(l)).isEqualTo("completed");
    }

    @Test
    void suppressionAddedAfterLaunchStopsTheSend() throws Exception {
        Launched l = launched(2);
        String suppressed = l.emails().get(0);
        assertThat(send("POST", "/api/suppressions", l.account().token(),
                "{\"email\":\"" + suppressed + "\"}").statusCode()).isEqualTo(201);

        drain();

        assertThat(messageStatus(l, suppressed)).isEqualTo("canceled");
        assertThat(mock.deliveriesTo(suppressed)).isZero();
        assertThat(messageStatus(l, l.emails().get(1))).isEqualTo("sent");
        assertThat(campaignStatus(l)).isEqualTo("completed");
    }

    @Test
    void crashedWorkerLosesNothingAndNeverDuplicatesWithIdempotentProvider() throws Exception {
        Launched l = launched(5);
        mock.crashAfterSend(l.emails().get(2));

        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(MockEmailProvider.SimulatedCrash.class);
        assertThat(ownerCount("select count(*) from email_messages where campaign_id = ?::uuid "
                + "and status = 'sending'", l.campaignId())).isGreaterThanOrEqualTo(1);

        expireLeases(l.campaignId());
        drain();

        for (String email : l.emails()) {
            assertThat(messageStatus(l, email)).isEqualTo("sent");
            assertThat(mock.deliveriesTo(email)).isEqualTo(1);
        }
        assertThat(campaignStatus(l)).isEqualTo("completed");
    }

    @Test
    void crashWithNonIdempotentProviderMarksUnknownInsteadOfResending() throws Exception {
        mock.setSupportsIdempotencyKeys(false);
        Launched l = launched(5);
        String crashed = l.emails().get(2);
        mock.crashAfterSend(crashed);

        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(MockEmailProvider.SimulatedCrash.class);

        expireLeases(l.campaignId());
        drain();

        int unknown = 0;
        for (String email : l.emails()) {
            String status = messageStatus(l, email);
            assertThat(status).isIn("sent", "unknown");
            assertThat(mock.deliveriesTo(email)).isLessThanOrEqualTo(1);
            if (status.equals("unknown")) {
                unknown++;
            }
        }
        assertThat(messageStatus(l, crashed)).isEqualTo("unknown");
        assertThat(mock.deliveriesTo(crashed)).isEqualTo(1);
        assertThat(unknown).isGreaterThanOrEqualTo(1);
        assertThat(ownerCount("select count(*) from email_messages where campaign_id = ?::uuid "
                + "and status in ('queued', 'sending')", l.campaignId())).isZero();
        assertThat(campaignStatus(l)).isEqualTo("completed");
    }

    @Test
    void concurrentWorkersNeverSendTheSameMessageTwice() throws Exception {
        Launched l = launched(40);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            futures.add(pool.submit(() -> {
                int attempted;
                do {
                    attempted = worker.runOnce();
                } while (attempted > 0);
            }));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        worker.runOnce();

        for (String email : l.emails()) {
            assertThat(mock.deliveriesTo(email)).isEqualTo(1);
        }
        assertThat(ownerCount("select count(*) from email_messages where campaign_id = ?::uuid "
                + "and status = 'sent'", l.campaignId())).isEqualTo(40);
        assertThat(campaignStatus(l)).isEqualTo("completed");
    }

    @Test
    void cancelReleasesQuotaAndNothingIsSent() throws Exception {
        Launched l = launched(3);
        Account viewer = addMember(l.account(), "viewer");
        Account other = register("Org B");
        assertThat(extractInt(send("GET", "/api/usage", l.account().token(), null).body(), "used"))
                .isEqualTo(3);

        assertThat(send("POST", "/api/campaigns/" + l.campaignId() + "/cancel", viewer.token(), null)
                .statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/campaigns/" + l.campaignId() + "/cancel", other.token(), null)
                .statusCode()).isEqualTo(404);

        assertThat(send("POST", "/api/campaigns/" + l.campaignId() + "/cancel", l.account().token(), null)
                .statusCode()).isEqualTo(200);
        assertThat(campaignStatus(l)).isEqualTo("canceled");
        assertThat(extractInt(send("GET", "/api/usage", l.account().token(), null).body(), "used"))
                .isZero();

        drain();

        for (String email : l.emails()) {
            assertThat(messageStatus(l, email)).isEqualTo("canceled");
            assertThat(mock.deliveriesTo(email)).isZero();
        }
        assertThat(send("POST", "/api/campaigns/" + l.campaignId() + "/cancel", l.account().token(), null)
                .statusCode()).isEqualTo(409);
    }

    @Test
    void workerRoleIsLeastPrivileged() throws Exception {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "flowmail_worker", "flowmail_worker");
             Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("select * from users"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("select * from templates"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("delete from email_messages"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.execute("update usage_counters set used = 0"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    private Launched launched(int contacts) throws Exception {
        Account account = register("Org A");
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < contacts; i++) {
            String email = "r" + i + "-" + UUID.randomUUID() + "@example.com";
            createContact(account, email);
            emails.add(email);
        }
        String campaignId = createCampaign(account, createTemplate(account, "Welcome", GOOD_HTML));
        assertThat(send("POST", "/api/campaigns/" + campaignId + "/launch", account.token(), null)
                .statusCode()).isEqualTo(202);
        return new Launched(account, campaignId, emails);
    }

    private void drain() {
        for (int i = 0; i < 200; i++) {
            if (worker.runOnce() == 0) {
                return;
            }
        }
    }

    private String campaignStatus(Launched l) throws Exception {
        return extract(send("GET", "/api/campaigns/" + l.campaignId(), l.account().token(), null).body(),
                "status");
    }

    private String messagesBody(Launched l) throws Exception {
        return send("GET", "/api/campaigns/" + l.campaignId() + "/messages?limit=100",
                l.account().token(), null).body();
    }

    private String messageStatus(Launched l, String email) throws Exception {
        Matcher m = Pattern.compile("\"email\":\"" + Pattern.quote(email) + "\",\"status\":\"([^\"]+)\"")
                .matcher(messagesBody(l));
        assertThat(m.find()).as("message for " + email).isTrue();
        return m.group(1);
    }

    private long attemptRows(Launched l) throws Exception {
        return ownerCount("select count(*) from delivery_attempts d join email_messages m on m.id = d.message_id "
                + "where m.campaign_id = ?::uuid", l.campaignId());
    }

    private void expireLeases(String campaignId) throws Exception {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement ps = c.prepareStatement("update email_messages "
                     + "set locked_until = now() - interval '1 second' "
                     + "where campaign_id = ?::uuid and status = 'sending'")) {
            ps.setString(1, campaignId);
            ps.executeUpdate();
        }
    }
}