package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The activity section (M4e slice 4): the application's own events and dispatch jobs over the
 * platform's scoped reads as the service account, behind {@code activity:view}, confined again here.
 */
class PlatformActivityAdminTest {

  static final String VIEW = "orders:admin:activity:view";

  @TempDir Path dir;

  FakePlatform platform;
  App app;
  TestClient client;

  @BeforeEach
  void start() {
    platform = FakePlatform.start();
    platform.respondGet("/api/events", 200, events());
    platform.respondGet("/api/events/ev_1", 200, eventDetail("ev_1", "orders:sales:order:placed", "{\"orderId\":\"o1\"}"));
    platform.respondGet("/api/events/ev_other", 200, eventDetail("ev_other", "billing:invoice:invoice:issued", "{}"));
    platform.respondGet("/api/dispatch-jobs", 200, jobs());
    platform.respondGet("/api/dispatch-jobs/event/ev_1", 200, "[" + job("dj_1", "ev_1", "orders:sales:order:placed", "COMPLETED") + "," + job("dj_x", "ev_1", "billing:x:y:z", "PENDING") + "]");
    platform.respondGet("/api/dispatch-jobs/dj_1", 200, job("dj_1", "ev_1", "orders:sales:order:placed", "COMPLETED"));
    platform.respondGet("/api/dispatch-jobs/dj_1/attempts", 200, attempts());
    platform.respondGet("/api/dispatch-jobs/dj_x", 200, job("dj_x", "ev_1", "billing:x:y:z", "PENDING"));
    platform.respondGet("/api/dispatch-jobs/dj_x/attempts", 200, attempts());
    app = starting(Map.of());
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
  }

  App starting(Map<String, String> more) {
    Map<String, String> env = new java.util.HashMap<>(Receiving.env(platform));
    env.putAll(more);
    AppBuilder b = App.builder().configDirectory(dir).environment(env);
    b.module(PlatformAdminTest.http()).module(AdminModule.of(PlatformUsersAdminTest.APP)).module(PlatformActivityAdminModule.of(PlatformUsersAdminTest.APP)).module(new FlowCatalystModule());
    return b.start();
  }

  @AfterEach
  void stop() {
    app.close();
    platform.close();
  }

  // ---- platform bodies ----------------------------------------------------------------------

  static String eventRead(String id, String type) {
    String[] p = type.split(":");
    return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"source\":\"" + p[0] + ":" + p[1] + "\",\"subject\":\"s-" + id
        + "\",\"time\":\"2026-10-16T08:0" + id.length() % 10 + ":00.000Z\",\"application\":\"" + p[0] + "\",\"subdomain\":\"" + p[1]
        + "\",\"aggregate\":\"" + p[2] + "\",\"correlationId\":\"c-" + id + "\",\"projectedAt\":\"2026-10-16T08:00:01.000Z\"}";
  }

  static String events() {
    return "[" + eventRead("ev_2", "orders:sales:order:shipped") + "," + eventRead("ev_1", "orders:sales:order:placed") + ","
        + eventRead("ev_other", "billing:invoice:invoice:issued") + "]";
  }

  static String eventDetail(String id, String type, String data) {
    String read = eventRead(id, type);
    return read.substring(0, read.length() - 1) + ",\"specVersion\":\"1.0\",\"data\":" + data
        + ",\"contextData\":[{\"key\":\"region\",\"value\":\"eu\"}],\"causationId\":\"cause-1\",\"deduplicationId\":\"d-1\"}";
  }

  static String job(String id, String eventId, String code, String status) {
    return "{\"id\":\"" + id + "\",\"eventId\":\"" + eventId + "\",\"subscriptionId\":\"sub_1\",\"code\":\"" + code + "\",\"status\":\"" + status
        + "\",\"kind\":\"EVENT\",\"targetUrl\":\"https://user:pw@billing.example.com/hooks/x?token=URL-SECRET#frag\",\"mode\":\"IMMEDIATE\","
        + "\"descriptor\":\"Billing sync\",\"createdAt\":\"2026-10-16T08:00:02.000Z\",\"updatedAt\":\"2026-10-16T08:00:03.000Z\","
        + "\"attemptCount\":1,\"protocol\":\"HTTP_WEBHOOK\",\"timeoutSeconds\":30,\"maxRetries\":3,\"retryStrategy\":\"EXPONENTIAL\","
        + "\"payload\":\"{\\\"secret\\\":\\\"PAYLOAD-LEAK\\\"}\",\"serviceAccountId\":\"sa_LEAK\"}";
  }

  static String jobs() {
    return "[" + job("dj_2", "ev_2", "orders:sales:order:shipped", "FAILED") + "," + job("dj_1", "ev_1", "orders:sales:order:placed", "COMPLETED")
        + "," + job("dj_x", "ev_x", "billing:x:y:z", "PENDING") + "]";
  }

  static String attempts() {
    return "[{\"attemptNumber\":1,\"attemptedAt\":\"2026-10-16T08:00:03.000Z\",\"completedAt\":\"2026-10-16T08:00:04.000Z\","
        + "\"durationMillis\":812,\"responseCode\":500,\"responseBody\":\"" + "x".repeat(5000) + "\",\"success\":false,"
        + "\"errorMessage\":\"HTTP 500\",\"errorType\":\"HTTP_ERROR\",\"request\":{\"signedBy\":\"sa-LEAK\"}},"
        + "{\"attemptNumber\":2,\"attemptedAt\":\"2026-10-16T08:01:03.000Z\",\"durationMillis\":50,\"responseCode\":200,"
        + "\"responseBody\":\"ok\",\"success\":true}]";
  }

  TestResponse get(String path, String perms) {
    return client.get(path).header("X-Perms", perms).send();
  }

  List<FakePlatform.Recorded> platformCalls() {
    return platform.requests().stream().filter(r -> r.path().startsWith("/api/")).toList();
  }

  // ---- events --------------------------------------------------------------------------------

  @Test
  void eventsAreTheApplicationsOnlyAndAskedForByApplication() {
    TestResponse r = get("/api/admin/events", VIEW);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("items")).extracting(n -> n.get("id").asString()).containsExactly("ev_2", "ev_1");
    assertThat(r.tree().get("items").get(0).get("subject").asString()).isEqualTo("s-ev_2");
    assertThat(r.body()).doesNotContain("billing");
    FakePlatform.Recorded read = platformCalls().getFirst();
    assertThat(read.path()).isEqualTo("/api/events");
    assertThat(read.query()).contains("applications=orders").contains("limit=51").contains("offset=0");
    assertThat(read.headers().get("authorization")).startsWith("Bearer ");
  }

  @Test
  void eventFiltersAreSentAndPagingIsByOffsetWithALookAhead() {
    get("/api/admin/events?type=orders:sales:order:placed&subject=s&correlationId=c&clientId=clt_1&since=2026-10-16T08:00:00Z&until=2026-10-17T08:00:00Z&limit=2&after=4", VIEW);
    String query = java.net.URLDecoder.decode(platformCalls().getFirst().query(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(query).contains("type=orders:sales:order:placed").contains("subject=s").contains("correlationId=c").contains("clientId=clt_1")
        .contains("since=2026-10-16T08:00:00Z").contains("until=2026-10-17T08:00:00Z").contains("limit=3").contains("offset=4");

    // three rows for a limit of two: a next page begins at offset + 2
    TestResponse page = get("/api/admin/events?limit=2&after=4", VIEW);
    assertThat(page.tree().get("items")).hasSize(2);
    assertThat(page.tree().get("nextCursor").asString()).isEqualTo("6");
    assertThat(get("/api/admin/events?limit=3", VIEW).tree().has("nextCursor")).isFalse();
    assertThat(get("/api/admin/events?after=x", VIEW).tree().get("error").asString()).isEqualTo("INVALID_CURSOR");
    assertThat(get("/api/admin/events?since=yesterday", VIEW).tree().get("error").asString()).isEqualTo("INVALID_TIMESTAMP");
  }

  @Test
  void anotherApplicationsTypeIsAnEmptyPageAndTheOtherApplicationIsNeverAsked() {
    platform.clearRequests();
    TestResponse r = get("/api/admin/events?type=billing:invoice:invoice:issued", VIEW);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("items")).isEmpty();
    assertThat(platformCalls()).isEmpty();
    assertThat(get("/api/admin/dispatch-jobs?code=billing:x:y:z", VIEW).tree().get("items")).isEmpty();
    assertThat(platformCalls()).isEmpty();
  }

  @Test
  void anEventHasItsPayloadContextAndOnlyTheApplicationsJobs() {
    TestResponse r = get("/api/admin/events/ev_1", VIEW);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("event").get("type").asString()).isEqualTo("orders:sales:order:placed");
    assertThat(r.tree().get("data").asString()).contains("orderId");
    assertThat(r.tree().get("dataTruncated").asBoolean()).isFalse();
    assertThat(r.tree().get("context").get(0).get("key").asString()).isEqualTo("region");
    assertThat(r.tree().get("causationId").asString()).isEqualTo("cause-1");
    assertThat(r.tree().get("dispatchJobs")).extracting(n -> n.get("id").asString()).containsExactly("dj_1");
    assertThat(r.body()).doesNotContain("billing:x");
  }

  @Test
  void anotherApplicationsEventIsA404AndItsJobsAreNotAsked() {
    platform.clearRequests();
    TestResponse r = get("/api/admin/events/ev_other", VIEW);

    assertThat(r.status()).isEqualTo(404);
    assertThat(r.tree().get("error").asString()).isEqualTo("EVENT_NOT_FOUND");
    assertThat(r.body()).doesNotContain("billing");
    assertThat(platformCalls()).extracting(FakePlatform.Recorded::path).containsExactly("/api/events/ev_other");

    platform.clearRequests();
    assertThat(get("/api/admin/events/..%2Fusers", VIEW).status()).isEqualTo(404);
    assertThat(platformCalls()).isEmpty();
    platform.respondGet("/api/events/ev_gone", 404, "{\"error\":\"Event_NOT_FOUND\"}");
    assertThat(get("/api/admin/events/ev_gone", VIEW).tree().get("error").asString()).isEqualTo("EVENT_NOT_FOUND");
  }

  @Test
  void aLongPayloadIsCutAndSaid() {
    platform.respondGet("/api/events/ev_1", 200, eventDetail("ev_1", "orders:sales:order:placed", "\"" + "y".repeat(25_000) + "\""));
    JsonNode tree = get("/api/admin/events/ev_1", VIEW).tree();
    assertThat(tree.get("data").asString()).hasSize(20_000);
    assertThat(tree.get("dataTruncated").asBoolean()).isTrue();
  }

  // ---- dispatch jobs -------------------------------------------------------------------------

  @Test
  void jobsAreTheApplicationsOnlyWithTheTargetShownWithoutItsCredentials() {
    TestResponse r = get("/api/admin/dispatch-jobs?status=FAILED", VIEW);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("items")).extracting(n -> n.get("id").asString()).containsExactly("dj_2", "dj_1");
    assertThat(r.tree().get("items").get(0).get("targetUrl").asString()).isEqualTo("https://billing.example.com/hooks/x");
    assertThat(r.tree().get("items").get(0).get("descriptor").asString()).isEqualTo("Billing sync");
    assertThat(r.body()).doesNotContain("URL-SECRET").doesNotContain("user:pw").doesNotContain("PAYLOAD-LEAK").doesNotContain("sa_LEAK").doesNotContain("billing:x");
    FakePlatform.Recorded read = platformCalls().getFirst();
    assertThat(read.query()).contains("applications=orders").contains("statuses=FAILED").contains("limit=51");
    assertThat(get("/api/admin/dispatch-jobs?status=drop%20table", VIEW).tree().get("error").asString()).isEqualTo("INVALID_STATUS");
  }

  @Test
  void aJobHasItsAttemptsWithBoundedResponseBodiesAndNothingElseThePlatformSent() {
    TestResponse r = get("/api/admin/dispatch-jobs/dj_1", VIEW);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("job").get("code").asString()).isEqualTo("orders:sales:order:placed");
    assertThat(r.tree().get("protocol").asString()).isEqualTo("HTTP_WEBHOOK");
    assertThat(r.tree().get("maxRetries").asInt()).isEqualTo(3);
    JsonNode attempts = r.tree().get("attempts");
    assertThat(attempts).hasSize(2);
    assertThat(attempts.get(0).get("responseCode").asInt()).isEqualTo(500);
    assertThat(attempts.get(0).get("responseBody").asString()).hasSize(4000);
    assertThat(attempts.get(0).get("responseBodyTruncated").asBoolean()).isTrue();
    assertThat(attempts.get(1).get("responseBodyTruncated").asBoolean()).isFalse();
    assertThat(attempts.get(0).get("errorType").asString()).isEqualTo("HTTP_ERROR");
    assertThat(r.body()).doesNotContain("sa-LEAK").doesNotContain("PAYLOAD-LEAK").doesNotContain("URL-SECRET");
  }

  @Test
  void anotherApplicationsJobIsA404AndItsAttemptsAreNeverAsked() {
    platform.clearRequests();
    TestResponse r = get("/api/admin/dispatch-jobs/dj_x", VIEW);

    assertThat(r.status()).isEqualTo(404);
    assertThat(r.tree().get("error").asString()).isEqualTo("DISPATCH_JOB_NOT_FOUND");
    assertThat(r.body()).doesNotContain("billing");
    assertThat(platformCalls()).extracting(FakePlatform.Recorded::path).containsExactly("/api/dispatch-jobs/dj_x");

    platform.clearRequests();
    assertThat(get("/api/admin/dispatch-jobs/a%2Fb", VIEW).status()).isEqualTo(404);
    assertThat(platformCalls()).isEmpty();
  }

  // ---- the gate and the platform's answers -----------------------------------------------------

  @Test
  void everyOperationNeedsTheActivityPermissionAndARefusedCallerNeverReachesThePlatform() {
    for (String path : List.of("/api/admin/events", "/api/admin/events/ev_1", "/api/admin/dispatch-jobs", "/api/admin/dispatch-jobs/dj_1")) {
      for (String held : List.of("", "orders:admin:console:view", "orders:admin:users:view", "orders:admin:event-types:view", "orders:admin:activity:manage")) {
        assertThat(get(path, held).status()).as(path + " holding " + held).isEqualTo(403);
      }
      assertThat(get(path, "orders:admin:*:*").status()).as(path).isEqualTo(200);
      assertThat(get(path, VIEW).status()).as(path).isEqualTo(200);
    }
    assertThat(client.get("/api/admin/events").send().status()).isEqualTo(401);
    TestResponse none = get("/api/admin/events", "");
    assertThat(none.tree().get("message").asString()).isEqualTo("this needs the permission " + VIEW);
  }

  @Test
  void aRefusedCallerCallsNothing() {
    platform.clearRequests();
    get("/api/admin/events", "orders:admin:console:view");
    get("/api/admin/dispatch-jobs/dj_1", "orders:admin:users:view");
    assertThat(platformCalls()).isEmpty();
  }

  @Test
  void aPlatformRefusalOrOutageIsA502WithoutItsText() {
    platform.respondGet("/api/events", 403, "{\"error\":\"FORBIDDEN\",\"message\":\"internal-host-3\"}");
    TestResponse denied = get("/api/admin/events", VIEW);
    assertThat(denied.status()).isEqualTo(502);
    assertThat(denied.tree().get("message").asString()).contains("service account").contains("view permission");
    assertThat(denied.body()).doesNotContain("internal-host-3");

    platform.respondGet("/api/dispatch-jobs", 500, "{\"error\":\"boom 127.0.0.1\"}");
    TestResponse down = get("/api/admin/dispatch-jobs", VIEW);
    assertThat(down.status()).isEqualTo(502);
    assertThat(down.body()).doesNotContain("127.0.0.1");
  }

  @Test
  void theNavigationListsActivityOnlyForItsViewers() {
    JsonNode nav = get("/api/admin/navigation", "orders:admin:console:view," + VIEW).tree();
    assertThat(nav.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about", "activity");
    JsonNode none = get("/api/admin/navigation", "orders:admin:console:view").tree();
    assertThat(none.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about");
  }

  @Test
  void aTargetUrlLosesItsUserInfoQueryAndFragmentOnly() {
    assertThat(PlatformActivityAdminModule.withoutSecrets("https://u:p@h.example:8443/a/b?x=1#f")).isEqualTo("https://h.example:8443/a/b");
    assertThat(PlatformActivityAdminModule.withoutSecrets("https://h.example/a?x=1@y")).isEqualTo("https://h.example/a");
    assertThat(PlatformActivityAdminModule.withoutSecrets("https://h.example/a@b")).isEqualTo("https://h.example/a@b");
    assertThat(PlatformActivityAdminModule.withoutSecrets("not a url")).isEqualTo("not a url");
  }
}
