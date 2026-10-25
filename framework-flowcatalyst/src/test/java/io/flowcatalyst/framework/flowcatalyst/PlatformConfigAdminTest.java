package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Method;
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
 * The platform-config section (platform-config contract section 4): the application's configuration
 * as the service account, behind three permissions, with a secret's plaintext in the reveal answer only.
 */
class PlatformConfigAdminTest {

  static final ApplicationCode APP = new ApplicationCode("orders");
  static final String VIEW = "orders:admin:platform-config:view";
  static final String MANAGE = "orders:admin:platform-config:manage";
  static final String REVEAL = "orders:admin:platform-config:reveal";
  static final String ALL_CLIENTS = "orders:admin:platform-config:all-clients";
  static final String PLAINTEXT = "hunter2-PLAINTEXT-secret";
  static final String ENTERED = "entered-PLAINTEXT-secret";

  static final String LIST = "/api/platform-config/orders";
  static final String PROP = "/api/config/orders/ftp/password";

  @TempDir Path dir;

  FakePlatform platform;
  App app;
  TestClient client;

  @BeforeEach
  void start() {
    platform = FakePlatform.start();
    // A fake that leaks plaintext in a list and in a set answer, as a misbehaving platform might.
    platform.respondTo("GET", LIST, 200, list());
    platform.respondTo("PUT", PROP, 200, item("ftp", "password", "clt_1", "SECRET", PLAINTEXT, "FTP login"));
    platform.respondTo("DELETE", PROP, 204, "");
    platform.respondTo("GET", PROP + "/secret", 200, revealed());
    app = starting();
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
  }

  App starting() {
    AppBuilder b = App.builder().configDirectory(dir).environment(Receiving.env(platform));
    b.module(PlatformAdminTest.http()).module(AdminModule.of(APP)).module(PlatformConfigAdminModule.of(APP)).module(new FlowCatalystModule());
    return b.start();
  }

  @AfterEach
  void stop() {
    app.close();
    platform.close();
  }

  static String item(String section, String property, String clientId, String type, String value, String description) {
    return "{\"id\":\"cfg_1\",\"applicationCode\":\"orders\",\"section\":\"" + section + "\",\"property\":\"" + property + "\""
        + (clientId == null ? "" : ",\"clientId\":\"" + clientId + "\"")
        + ",\"scope\":\"" + (clientId == null ? "GLOBAL" : "CLIENT") + "\",\"valueType\":\"" + type + "\",\"value\":\"" + value + "\""
        + (description == null ? "" : ",\"description\":\"" + description + "\"")
        + ",\"createdAt\":\"2026-10-01T08:00:00Z\",\"updatedAt\":\"2026-10-23T10:00:00Z\"}";
  }

  static String list() {
    return "{\"items\":[" + item("general", "currency", null, "PLAIN", "ZAR", null) + ","
        + item("ftp", "password", "clt_1", "SECRET", PLAINTEXT, "FTP login") + ","
        + item("ftp", "password", "clt_2", "SECRET", "***", null) + ","
        + item("secrets", "api-key", "clt_1", "SECRET", PLAINTEXT, null) + "]}";
  }

  static String revealed() {
    return "{\"applicationCode\":\"orders\",\"section\":\"ftp\",\"property\":\"password\",\"clientId\":\"clt_1\",\"value\":\"" + PLAINTEXT
        + "\",\"updatedAt\":\"2026-10-23T10:00:00Z\"}";
  }

  /** As {@link #sendAs}, for a user who may name any client (the all-clients permission is held too). */
  TestResponse send(String method, String path, String perms, String body) {
    return sendAs(method, path, perms.isEmpty() ? ALL_CLIENTS : perms + "," + ALL_CLIENTS, body);
  }

  TestResponse sendAs(String method, String path, String perms, String body) {
    TestClient.Call call = client.request(Method.valueOf(method), path).header("X-Perms", perms);
    if (body != null) {
      call.jsonBody(body);
    }
    return call.send();
  }

  List<FakePlatform.Recorded> platformCalls() {
    return platform.requests().stream().filter(r -> r.path().startsWith("/api/")).toList();
  }

  static final String SET_BODY = "{\"value\":\"" + ENTERED + "\",\"valueType\":\"SECRET\",\"description\":\"FTP login\"}";

  // ---- list ----------------------------------------------------------------------------------

  @Test
  void theListMasksEverySecretWhateverThePlatformSent() {
    TestResponse r = send("GET", "/api/admin/platform-config", VIEW, null);

    assertThat(r.status()).isEqualTo(200);
    JsonNode items = r.tree().get("items");
    assertThat(items).hasSize(4);
    assertThat(items.get(0).get("value").asString()).isEqualTo("ZAR");
    assertThat(items.get(0).get("valueType").asString()).isEqualTo("PLAIN");
    assertThat(items.get(0).has("clientId")).isFalse();
    assertThat(items.get(1).get("value").asString()).isEqualTo("***");
    assertThat(items.get(1).get("valueType").asString()).isEqualTo("SECRET");
    assertThat(items.get(1).get("clientId").asString()).isEqualTo("clt_1");
    assertThat(items.get(1).get("description").asString()).isEqualTo("FTP login");
    assertThat(items.get(1).get("updatedAt").asString()).isEqualTo("2026-10-23T10:00:00Z");
    assertThat(r.body()).doesNotContain(PLAINTEXT);
    assertThat(platformCalls()).singleElement().satisfies(c -> assertThat(c.path()).isEqualTo(LIST));
  }

  @Test
  void theListCanBeNarrowedBySectionAndClient() {
    assertThat(send("GET", "/api/admin/platform-config?section=ftp", VIEW, null).tree().get("items")).hasSize(2);
    assertThat(send("GET", "/api/admin/platform-config?clientId=clt_1", VIEW, null).tree().get("items")).hasSize(2);
    assertThat(send("GET", "/api/admin/platform-config?section=ftp&clientId=clt_2", VIEW, null).tree().get("items")).hasSize(1);
    platform.clearRequests();
    TestResponse bad = send("GET", "/api/admin/platform-config?section=Bad%20Name", VIEW, null);
    assertThat(bad.status()).isEqualTo(400);
    assertThat(bad.tree().get("error").asString()).isEqualTo("INVALID_NAME");
    assertThat(platformCalls()).isEmpty();
  }

  // ---- set and delete ------------------------------------------------------------------------

  @Test
  void aSecretIsSetForAClientAndTheAnswerNeverHoldsAValue() {
    TestResponse r = send("PUT", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, SET_BODY);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("value").asString()).isEqualTo("***");
    assertThat(r.tree().get("valueType").asString()).isEqualTo("SECRET");
    assertThat(r.tree().get("clientId").asString()).isEqualTo("clt_1");
    assertThat(r.body()).doesNotContain(PLAINTEXT).doesNotContain(ENTERED);
    FakePlatform.Recorded call = platformCalls().getFirst();
    assertThat(call.method()).isEqualTo("PUT");
    assertThat(call.path()).isEqualTo(PROP);
    assertThat(call.query()).isEqualTo("clientId=clt_1");
    assertThat(call.json().get("value").asString()).isEqualTo(ENTERED);
    assertThat(call.json().get("valueType").asString()).isEqualTo("SECRET");
    assertThat(call.json().get("description").asString()).isEqualTo("FTP login");
  }

  @Test
  void aPlainValueIsSetAndEchoed() {
    platform.respondTo("PUT", "/api/config/orders/general/currency", 200, item("general", "currency", null, "PLAIN", "USD", null));

    TestResponse r = send("PUT", "/api/admin/platform-config/general/currency", MANAGE, "{\"value\":\"USD\",\"valueType\":\"PLAIN\"}");

    assertThat(r.tree().get("value").asString()).isEqualTo("USD");
    assertThat(r.tree().get("valueType").asString()).isEqualTo("PLAIN");
    assertThat(platformCalls().getFirst().query()).isEmpty();
  }

  @Test
  void aBadBodyIsRefusedBeforeAnyCall() {
    for (String body :
        List.of(
            "{\"value\":\"\",\"valueType\":\"SECRET\"}",
            "{\"value\":\"  \",\"valueType\":\"SECRET\"}",
            "{\"valueType\":\"SECRET\"}",
            "{\"value\":\"x\"}",
            "{\"value\":\"x\",\"valueType\":\"OTHER\"}",
            "{\"value\":\"" + "x".repeat(65_537) + "\",\"valueType\":\"PLAIN\"}",
            "{\"value\":\"x\",\"valueType\":\"PLAIN\",\"description\":\"" + "d".repeat(501) + "\"}")) {
      assertThat(send("PUT", "/api/admin/platform-config/ftp/password", MANAGE, body).status()).as(body.substring(0, Math.min(60, body.length()))).isEqualTo(400);
    }
    assertThat(platformCalls()).isEmpty();
  }

  @Test
  void aPropertyIsDeletedAndDeletingOneThatIsNotThereIsNotAnError() {
    assertThat(send("DELETE", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, null).status()).isEqualTo(204);
    FakePlatform.Recorded call = platformCalls().getFirst();
    assertThat(call.method()).isEqualTo("DELETE");
    assertThat(call.path()).isEqualTo(PROP);
    assertThat(call.query()).isEqualTo("clientId=clt_1");

    platform.respondTo("DELETE", "/api/config/orders/ftp/gone", 204, "");
    assertThat(send("DELETE", "/api/admin/platform-config/ftp/gone", MANAGE, null).status()).isEqualTo(204);
  }

  // ---- reveal --------------------------------------------------------------------------------

  @Test
  void aRevealAnswersThePlaintextNotCachedAndOnlyThere() {
    TestResponse r = send("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=clt_1", REVEAL, null);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("value").asString()).isEqualTo(PLAINTEXT);
    assertThat(r.tree().get("section").asString()).isEqualTo("ftp");
    assertThat(r.tree().get("property").asString()).isEqualTo("password");
    assertThat(r.tree().get("clientId").asString()).isEqualTo("clt_1");
    assertThat(r.tree().get("updatedAt").asString()).isEqualTo("2026-10-23T10:00:00Z");
    assertThat(r.header("Cache-Control")).contains("no-store");
    FakePlatform.Recorded call = platformCalls().getFirst();
    assertThat(call.method()).isEqualTo("GET");
    assertThat(call.path()).isEqualTo(PROP + "/secret");
    assertThat(call.query()).isEqualTo("clientId=clt_1");
  }

  @Test
  void revealIsNotImpliedByManageOrViewAndManageIsNotImpliedByReveal() {
    for (String held : List.of(VIEW, MANAGE)) {
      TestResponse r = send("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=clt_1", held, null);
      assertThat(r.status()).as(held).isEqualTo(403);
      assertThat(r.body()).doesNotContain(PLAINTEXT);
    }
    assertThat(send("PUT", "/api/admin/platform-config/ftp/password", REVEAL, SET_BODY).status()).isEqualTo(403);
    assertThat(platformCalls()).isEmpty();
    // the suggested admin role holds all three
    assertThat(send("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=clt_1", "orders:admin:*:*", null).status()).isEqualTo(200);
  }

  @Test
  void revealOfAMissingPropertyAndOfAPlainOneAreTheirOwnAnswers() {
    platform.respondTo("GET", "/api/config/orders/ftp/none/secret", 404, "{\"error\":\"NOT_FOUND\"}");
    TestResponse missing = send("POST", "/api/admin/platform-config/ftp/none/reveal", REVEAL, null);
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.tree().get("error").asString()).isEqualTo("CONFIG_NOT_FOUND");

    platform.respondTo("GET", "/api/config/orders/general/currency/secret", 400, "{\"error\":\"NOT_A_SECRET\",\"message\":\"the property is plain\"}");
    TestResponse plain = send("POST", "/api/admin/platform-config/general/currency/reveal", REVEAL, null);
    assertThat(plain.status()).isEqualTo(400);
    assertThat(plain.tree().get("error").asString()).isEqualTo("NOT_A_SECRET");
  }

  // ---- the gate, names, errors, logs -----------------------------------------------------------

  @Test
  void everyOperationNeedsItsOwnPermissionAndAWrongOneNeverReachesThePlatform() {
    record Op(String method, String path, String body, String needs) {}
    List<Op> ops =
        List.of(
            new Op("GET", "/api/admin/platform-config", null, VIEW),
            new Op("PUT", "/api/admin/platform-config/ftp/password", SET_BODY, MANAGE),
            new Op("DELETE", "/api/admin/platform-config/ftp/password", null, MANAGE),
            new Op("POST", "/api/admin/platform-config/ftp/password/reveal", null, REVEAL));
    List<String> all = List.of(VIEW, MANAGE, REVEAL, "orders:admin:users:view", "orders:admin:platform-config:other", "");
    for (Op op : ops) {
      for (String held : all) {
        if (held.equals(op.needs())) {
          continue;
        }
        TestResponse r = send(op.method(), op.path(), held, op.body());
        assertThat(r.status()).as(op.method() + " " + op.path() + " holding '" + held + "'").isEqualTo(403);
        assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
      }
    }
    assertThat(platformCalls()).isEmpty();
  }

  @Test
  void namesThatCouldChangeThePathAreRefusedWithoutACall() {
    List<String> badSections = List.of("Upper", "1abc", "a_b", "a.b", "..%2F..%2Fx", "x%20y", "a".repeat(65), "-a");
    // a property is exactly a secret name: [a-z0-9]+(-[a-z0-9]+)*, at most 100 characters
    List<String> badProperties = List.of("Upper", "a_b", "a.b", "..%2F..%2Fx", "x%20y", "-a", "a-", "a--b", "a".repeat(101));
    for (String bad : badSections) {
      assertRefused("/api/admin/platform-config/" + bad + "/password");
    }
    for (String bad : badProperties) {
      assertRefused("/api/admin/platform-config/ftp/" + bad);
    }
    TestResponse r = send("DELETE", "/api/admin/platform-config/Upper/password", MANAGE, null);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.tree().get("error").asString()).isEqualTo("INVALID_NAME");
    assertThat(platformCalls()).isEmpty();
  }

  private void assertRefused(String path) {
    assertThat(send("PUT", path, MANAGE, SET_BODY).status()).as(path).isIn(400, 404);
    assertThat(send("DELETE", path, MANAGE, null).status()).as(path).isIn(400, 404);
    assertThat(send("POST", path + "/reveal", REVEAL, null).status()).as(path).isIn(400, 404);
  }

  @Test
  void aPropertyNameIsExactlyWhatTheSecretsDriverCanResolve() {
    for (String ok : List.of("1abc", "api-key", "a", "0", "a".repeat(100), "a-1-b-2")) {
      assertThat(io.flowcatalyst.framework.secrets.SecretRef.isValidName(ok)).as(ok).isTrue();
      platform.respondTo("PUT", "/api/config/orders/secrets/" + ok, 200, item("secrets", ok, "clt_1", "SECRET", "***", null));
      assertThat(send("PUT", "/api/admin/platform-config/secrets/" + ok + "?clientId=clt_1", MANAGE, SET_BODY).status()).as(ok).isEqualTo(200);
    }
    // and a section is still the section rule
    assertThat(send("PUT", "/api/admin/platform-config/1abc/password", MANAGE, SET_BODY).status()).isEqualTo(400);
  }

  @Test
  void everyPlatformPathNamesThisApplicationAndThePlatformsRefusalsAreReportedWithoutItsText() {
    send("GET", "/api/admin/platform-config", VIEW, null);
    send("PUT", "/api/admin/platform-config/ftp/password", MANAGE, SET_BODY);
    send("DELETE", "/api/admin/platform-config/ftp/password", MANAGE, null);
    send("POST", "/api/admin/platform-config/ftp/password/reveal", REVEAL, null);
    assertThat(platformCalls()).hasSize(4).allMatch(c -> c.path().startsWith("/api/config/orders/") || c.path().equals(LIST));

    platform.respondTo("GET", PROP + "/secret", 403, "{\"error\":\"FORBIDDEN\",\"message\":\"internal-host-9 says no " + PLAINTEXT + "\"}");
    TestResponse forbidden = send("POST", "/api/admin/platform-config/ftp/password/reveal", REVEAL, null);
    assertThat(forbidden.status()).isEqualTo(502);
    assertThat(forbidden.body()).doesNotContain("internal-host-9").doesNotContain(PLAINTEXT);

    platform.respondTo("PUT", PROP, 500, "{\"error\":\"BOOM\",\"message\":\"db down " + ENTERED + "\"}");
    TestResponse failed = send("PUT", "/api/admin/platform-config/ftp/password", MANAGE, SET_BODY);
    assertThat(failed.status()).isEqualTo(502);
    assertThat(failed.tree().get("error").asString()).isEqualTo("PLATFORM_ERROR");
    assertThat(failed.body()).doesNotContain(ENTERED).doesNotContain("db down");
  }

  @Test
  void aClientThePlatformRefusesIsReportedAsThePlatformSaidIt() {
    platform.respondTo("PUT", PROP, 403, "{\"error\":\"CLIENT_ACCESS_DENIED\",\"message\":\"client is not available to this application\"}");

    TestResponse r = send("PUT", "/api/admin/platform-config/ftp/password?clientId=clt_9", MANAGE, SET_BODY);

    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().get("error").asString()).isEqualTo("CLIENT_ACCESS_DENIED");
  }

  @Test
  void theLogNamesThePrincipalAndThePropertyButNeverAValue() {
    try (LogCapture logs = LogCapture.start()) {
      send("PUT", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, SET_BODY);
      send("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=clt_1", REVEAL, null);
      send("DELETE", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, null);
      platform.respondTo("PUT", PROP, 500, "{\"error\":\"BOOM\"}");
      send("PUT", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, SET_BODY);

      String text = logs.text();
      assertThat(text).contains("platform config set by usr_1: ftp/password client clt_1");
      assertThat(text).contains("platform config revealed by usr_1: ftp/password client clt_1");
      assertThat(text).contains("platform config deleted by usr_1: ftp/password client clt_1");
      assertThat(text).doesNotContain(PLAINTEXT).doesNotContain(ENTERED);
    }
  }

  @Test
  void theSectionIsInTheNavigationForSomeoneWhoMayView() {
    TestResponse nav = send("GET", "/api/admin/navigation", "orders:admin:console:view," + VIEW, null);
    assertThat(nav.status()).isEqualTo(200);
    assertThat(nav.tree().get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about", "platform-config");
    assertThat(send("GET", "/api/admin/navigation", "orders:admin:console:view," + MANAGE, null).tree().get("sections"))
        .extracting(n -> n.get("id").asString())
        .containsExactly("about");
  }

  @Test
  void aModuleForAnotherApplicationThanTheConfiguredOneFailsStartUp() {
    AppBuilder b = App.builder().configDirectory(dir).environment(Receiving.env(platform));
    b.module(PlatformAdminTest.http()).module(AdminModule.of(new ApplicationCode("billing"))).module(PlatformConfigAdminModule.of(new ApplicationCode("billing"))).module(new FlowCatalystModule());
    org.assertj.core.api.Assertions.assertThatThrownBy(b::start).hasMessageContaining("permission code must start with the application 'orders'");
  }

  // ---- the client rule (contract section 4, Validation) -------------------------------------------

  @Test
  void aUserWithoutAllClientsMayNameOnlyTheirOwnClientAndNothingElseIsCalled() {
    for (String other : List.of("clt_9", "bevco", "ORDERS", "_platform")) {
      TestResponse put = sendAs("PUT", "/api/admin/platform-config/ftp/password?clientId=" + other, MANAGE, SET_BODY);
      assertThat(put.status()).as(other).isEqualTo(403);
      assertThat(put.tree().get("error").asString()).isEqualTo("CLIENT_FORBIDDEN");
      assertThat(sendAs("DELETE", "/api/admin/platform-config/ftp/password?clientId=" + other, MANAGE, null).status()).isEqualTo(403);
      TestResponse reveal = sendAs("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=" + other, REVEAL, null);
      assertThat(reveal.status()).isEqualTo(403);
      assertThat(reveal.tree().get("error").asString()).isEqualTo("CLIENT_FORBIDDEN");
      assertThat(sendAs("GET", "/api/admin/platform-config?clientId=" + other, VIEW, null).status()).isEqualTo(403);
    }
    assertThat(platformCalls()).isEmpty();
  }

  @Test
  void theOwnClientByIdentifierIsAllowedAndTheStoredIdItAnswersWithIsLearned() {
    platform.respondTo("PUT", PROP, 200, item("ftp", "password", "clt_1", "SECRET", "***", null));

    TestResponse set = sendAs("PUT", "/api/admin/platform-config/ftp/password?clientId=orders", MANAGE, SET_BODY);

    assertThat(set.status()).isEqualTo(200);
    assertThat(set.tree().get("clientId").asString()).isEqualTo("clt_1"); // the stored id, not the identifier asked for
    assertThat(platformCalls().getFirst().query()).isEqualTo("clientId=orders");
    // now the same client by its stored id is the user's own too
    assertThat(sendAs("DELETE", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, null).status()).isEqualTo(204);
    assertThat(sendAs("DELETE", "/api/admin/platform-config/ftp/password?clientId=clt_2", MANAGE, null).status()).isEqualTo(403);
  }

  @Test
  void aRevealAnswersTheStoredClientIdEvenWhenTheRequestNamedTheIdentifier() {
    TestResponse r = sendAs("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=orders", REVEAL, null);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("clientId").asString()).isEqualTo("clt_1");
    assertThat(platformCalls().getFirst().query()).isEqualTo("clientId=orders");
  }

  @Test
  void aUserWithoutAllClientsSeesGlobalRowsAndTheirOwnClientsRowsOnceTheStoredIdIsKnown() {
    TestResponse before = sendAs("GET", "/api/admin/platform-config", VIEW, null);
    assertThat(before.tree().get("items")).extracting(n -> n.get("property").asString()).containsExactly("currency");

    sendAs("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=orders", REVEAL, null); // learns orders = clt_1
    TestResponse after = sendAs("GET", "/api/admin/platform-config", VIEW, null);

    assertThat(after.tree().get("items")).hasSize(3); // currency, and clt_1's two rows; never clt_2's
    assertThat(after.body()).doesNotContain("clt_2");
    // and with the permission, everyone's
    assertThat(send("GET", "/api/admin/platform-config", VIEW, null).tree().get("items")).hasSize(4);
  }

  @Test
  void theListFilterAcceptsAnIdentifierOnceKnownAndComparesItAsTheStoredId() {
    // before the pair is known an identifier matches nothing (documented limitation) ...
    assertThat(send("GET", "/api/admin/platform-config?clientId=orders", VIEW, null).tree().get("items")).isEmpty();

    send("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=orders", REVEAL, null);
    TestResponse r = send("GET", "/api/admin/platform-config?clientId=orders", VIEW, null);

    // ... and afterwards it is clt_1's rows, echoed with the stored id
    assertThat(r.tree().get("items")).hasSize(2);
    assertThat(r.tree().get("items")).allMatch(n -> n.get("clientId").asString().equals("clt_1"));
  }

  // ---- on behalf of --------------------------------------------------------------------------------

  @Test
  void theSignedInPrincipalIsSentAsOnBehalfOfOnSetDeleteAndRevealOnly() {
    send("PUT", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, SET_BODY);
    send("DELETE", "/api/admin/platform-config/ftp/password?clientId=clt_1", MANAGE, null);
    send("POST", "/api/admin/platform-config/ftp/password/reveal?clientId=clt_1", REVEAL, null);
    send("GET", "/api/admin/platform-config", VIEW, null);

    List<FakePlatform.Recorded> calls = platformCalls();
    assertThat(calls).hasSize(4);
    for (FakePlatform.Recorded c : calls.subList(0, 3)) {
      assertThat(c.headers().get("x-on-behalf-of")).as(c.method()).isEqualTo("usr_1");
      assertThat(c.headers().get("authorization")).startsWith("Bearer ");
    }
    assertThat(calls.get(3).headers()).doesNotContainKey("x-on-behalf-of");
  }

  @Test
  void theOnBehalfOfTextIsCleanedAndBounded() {
    assertThat(PlatformClient.onBehalfOfHeader(null)).isNull();
    assertThat(PlatformClient.onBehalfOfHeader("  \n\t ")).isNull();
    assertThat(PlatformClient.onBehalfOfHeader("  usr_1\r\nX-Evil: 1  ")).isEqualTo("usr_1X-Evil: 1");
    assertThat(PlatformClient.onBehalfOfHeader("anna@example.com")).isEqualTo("anna@example.com");
    assertThat(PlatformClient.onBehalfOfHeader("\u00e9a")).isEqualTo("a");
    assertThat(PlatformClient.onBehalfOfHeader("x".repeat(300))).hasSize(256);
  }

  // ---- the secrets cache ---------------------------------------------------------------------------

  @Test
  void aWriteOrDeleteDropsTheCachedSecretOfThatClientSoTheNextLookupSeesIt() {
    app.close();
    AppBuilder b = App.builder().configDirectory(dir).environment(Receiving.env(platform));
    b.module(PlatformAdminTest.http())
        .module(AdminModule.of(APP))
        .module(PlatformConfigAdminModule.of(APP))
        .module(new FlowCatalystModule())
        .module(io.flowcatalyst.framework.secrets.SecretsModule.builder().store("platform", PlatformSecrets.store()).build());
    app = b.start();
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
    var resolver = app.get(io.flowcatalyst.framework.secrets.SecretResolver.class);
    var acme = new io.flowcatalyst.framework.context.ClientId("clt_1");
    var ref = io.flowcatalyst.framework.secrets.SecretRef.of("api-key");
    String path = "/api/config/orders/secrets/api-key";
    platform.respondTo("GET", path + "/secret", 200, revealed("v1"));
    platform.respondTo("PUT", path, 200, item("secrets", "api-key", "clt_1", "SECRET", "***", null));
    platform.respondTo("DELETE", path, 204, "");

    assertThat(resolver.require(acme, ref).reveal()).isEqualTo("v1");
    platform.respondTo("GET", path + "/secret", 200, revealed("v2"));
    assertThat(resolver.require(acme, ref).reveal()).isEqualTo("v1"); // cached

    assertThat(send("PUT", "/api/admin/platform-config/secrets/api-key?clientId=clt_1", MANAGE, SET_BODY).status()).isEqualTo(200);
    assertThat(resolver.require(acme, ref).reveal()).isEqualTo("v2");

    platform.respondTo("GET", path + "/secret", 404, "{\"error\":\"Config_NOT_FOUND\"}");
    assertThat(send("DELETE", "/api/admin/platform-config/secrets/api-key?clientId=clt_1", MANAGE, null).status()).isEqualTo(204);
    assertThat(resolver.resolve(acme, ref)).isInstanceOf(io.flowcatalyst.framework.secrets.SecretResult.NotFound.class);
  }

  private static String revealed(String value) {
    return "{\"applicationCode\":\"orders\",\"section\":\"secrets\",\"property\":\"api-key\",\"clientId\":\"clt_1\",\"value\":\"" + value
        + "\",\"updatedAt\":\"2026-10-23T10:00:00Z\"}";
  }
}
