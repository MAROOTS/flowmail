package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CampaignLaunchTest extends ApiTestSupport {

    private HttpResponse<String> launch(Account account, String campaignId) throws Exception {
        return send("POST", "/api/campaigns/" + campaignId + "/launch", account.token(), null);
    }

    @Test
    void launchQueuesOneMessagePerEligibleContactAndReservesQuota() throws Exception {
        Account a = register("Org A");
        createContact(a, "a@example.com");
        createContact(a, "b@example.com");
        createContact(a, "c@example.com");
        assertThat(send("POST", "/api/suppressions", a.token(), "{\"email\":\"c@example.com\"}")
                .statusCode()).isEqualTo(201);

        String campaignId = createCampaign(a, createTemplate(a, "Welcome", GOOD_HTML));
        assertThat(send("GET", "/api/campaigns/" + campaignId, a.token(), null).body())
                .contains("\"status\":\"draft\"");

        HttpResponse<String> launched = launch(a, campaignId);
        assertThat(launched.statusCode()).isEqualTo(202);
        assertThat(launched.body()).contains("\"status\":\"queued\"").contains("\"queued\":2");
        assertThat(extractInt(launched.body(), "recipientCount")).isEqualTo(2);

        String messages = send("GET", "/api/campaigns/" + campaignId + "/messages", a.token(), null).body();
        assertThat(messages).contains("a@example.com").contains("b@example.com").doesNotContain("c@example.com");

        String usage = send("GET", "/api/usage", a.token(), null).body();
        assertThat(extractInt(usage, "used")).isEqualTo(2);

        assertThat(launch(a, campaignId).statusCode()).isEqualTo(409);
        assertThat(ownerCount("select count(*) from email_messages where campaign_id = ?::uuid", campaignId))
                .isEqualTo(2);
    }

    @Test
    void launchRefusedWithoutUnsubscribeLinkOrRecipients() throws Exception {
        Account a = register("Org A");
        createContact(a, "a@example.com");
        String noLinkCampaign = createCampaign(a, createTemplate(a, "NoLink", "<p>Hi</p>"));

        HttpResponse<String> refused = launch(a, noLinkCampaign);
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("missing_unsubscribe_link");
        assertThat(send("GET", "/api/campaigns/" + noLinkCampaign, a.token(), null).body())
                .contains("\"status\":\"draft\"");

        Account empty = register("Empty Org");
        String emptyCampaign = createCampaign(empty, createTemplate(empty, "Welcome", GOOD_HTML));
        HttpResponse<String> none = launch(empty, emptyCampaign);
        assertThat(none.statusCode()).isEqualTo(400);
        assertThat(none.body()).contains("no_recipients");
    }

    @Test
    void launchOverQuotaIsRejectedAndLeavesNothingBehind() throws Exception {
        Account a = register("Org A");
        setMonthlyLimit(a, 1);
        createContact(a, "a@example.com");
        createContact(a, "b@example.com");
        String campaignId = createCampaign(a, createTemplate(a, "Welcome", GOOD_HTML));

        HttpResponse<String> refused = launch(a, campaignId);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("quota_exceeded");

        assertThat(send("GET", "/api/campaigns/" + campaignId, a.token(), null).body())
                .contains("\"status\":\"draft\"");
        assertThat(ownerCount("select count(*) from email_messages where campaign_id = ?::uuid", campaignId))
                .isZero();
        assertThat(extractInt(send("GET", "/api/usage", a.token(), null).body(), "used")).isZero();
    }

    @Test
    void concurrentLaunchesNeverExceedQuota() throws Exception {
        Account a = register("Org A");
        setMonthlyLimit(a, 10);
        for (int i = 0; i < 6; i++) {
            createContact(a, "c" + i + "@example.com");
        }
        String templateId = createTemplate(a, "Welcome", GOOD_HTML);
        String first = createCampaign(a, templateId);
        String second = createCampaign(a, templateId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (String campaignId : List.of(first, second)) {
            futures.add(pool.submit(() -> {
                start.await();
                return launch(a, campaignId).statusCode();
            }));
        }
        start.countDown();

        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> future : futures) {
            codes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(codes).containsExactlyInAnyOrder(202, 409);
        assertThat(extractInt(send("GET", "/api/usage", a.token(), null).body(), "used")).isEqualTo(6);
        assertThat(ownerCount("select count(*) from email_messages where tenant_id = ?::uuid", a.tenantId()))
                .isEqualTo(6);
    }

    @Test
    void launchRespectsRolesAndTenantBoundaries() throws Exception {
        Account owner = register("Org A");
        Account other = register("Org B");
        Account viewer = addMember(owner, "viewer");
        createContact(owner, "a@example.com");
        String templateId = createTemplate(owner, "Welcome", GOOD_HTML);
        String campaignId = createCampaign(owner, templateId);

        assertThat(launch(viewer, campaignId).statusCode()).isEqualTo(403);
        assertThat(launch(other, campaignId).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/campaigns/" + campaignId, other.token(), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/campaigns/" + campaignId + "/messages", other.token(), null).statusCode())
                .isEqualTo(404);
        assertThat(send("POST", "/api/campaigns", other.token(), campaignJson(templateId)).statusCode())
                .isEqualTo(404);

        assertThat(send("GET", "/api/campaigns/" + campaignId, viewer.token(), null).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/campaigns", viewer.token(), campaignJson(templateId)).statusCode())
                .isEqualTo(403);

        assertThat(send("GET", "/api/campaigns/" + campaignId, owner.token(), null).body())
                .contains("\"status\":\"draft\"");
        assertThat(launch(owner, campaignId).statusCode()).isEqualTo(202);
    }
}