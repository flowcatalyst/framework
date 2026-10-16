package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The users section (M4e slice 1): users and invitations over the platform's application-user routes
 * as the service account, behind the admin permissions, confined to the application, with the invite
 * link treated as the credential it is.
 */
class PlatformUsersAdminTest {

  static final ApplicationCode APP = new ApplicationCode("orders");
  static final String VIEW = "orders:admin:users:view";
  static final String INVITE = "orders:admin:users:invite";
  static final String RESEND = "orders:admin:users:resend";
  static final String REVOKE = "orders:admin:users:revoke";
  static final String LINK = "https://platform.example/set-password?token=SECRET-LINK-TOKEN-1";
  static final String LINK_2 = "https://platform.example/set-password?token=SECRET-LINK-TOKEN-2";

  static final String USERS = "/api/applications/orders/users";
  static final String INVITATIONS = "/api/applications/orders/invitations";

  @TempDir Path dir;

  FakePlatform platform;
  App app;
  TestClient client;

  @BeforeEach
  void start() {
    platform = FakePlatform.start();
    platform.respondTo("GET", USERS, 200, users());
    platform.respondTo("GET", USERS + "/prn_1", 200, user("prn_1", "ann@example.com", "Ann", null, "orders:clerk", "billing:admin"));
    platform.respondTo("GET", INVITATIONS, 200, invitations());
    platform.respondTo("POST", USERS, 201, "{\"id\":\"prn_9\",\"inviteLink\":\"" + LINK + "\",\"invitationSent\":false}");
    platform.respondTo("POST", INVITATIONS + "/prn_2/resend", 200, "{\"inviteLink\":\"" + LINK_2 + "\",\"expiresAt\":\"2026-10-20T10:00:00.000Z\"}");
    platform.respondTo("DELETE", INVITATIONS + "/prn_2", 204, "");
    app = starting(Map.of());
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
  }

  App starting(Map<String, String> more) {
    Map<String, String> env = new java.util.HashMap<>(Receiving.env(platform));
    env.putAll(more);
    AppBuilder b = App.builder().configDirectory(dir).environment(env);
    b.module(PlatformAdminTest.http()).module(AdminModule.of(APP)).module(PlatformUsersAdminModule.of(APP)).module(new FlowCatalystModule());
    return b.start();
  }

  @AfterEach
  void stop() {
    app.close();
    platform.close();
  }

  // ---- platform bodies ----------------------------------------------------------------------

  static String user(String id, String email, String name, String invitation, String... roles) {
    List<String> quoted = new ArrayList<>();
    for (String r : roles) {
      quoted.add("\"" + r + "\"");
    }
    // The platform never sends these; they are here so a copy-everything projection would show them.
    return "{\"id\":\"" + id + "\",\"email\":\"" + email + "\",\"name\":\"" + name + "\",\"active\":true,\"clientId\":\"clt_1\","
        + "\"idpType\":\"INTERNAL\",\"roles\":[" + String.join(",", quoted) + "]"
        + (invitation == null ? "" : ",\"invitation\":\"" + invitation + "\"")
        + ",\"lastLoginAt\":\"2026-10-01T08:00:00.000Z\",\"createdAt\":\"2026-09-01T08:00:00.000Z\","
        + "\"passwordHash\":\"$argon2id$LEAK\",\"inviteLink\":\"" + LINK + "\",\"token\":\"LEAK-TOKEN\"}";
  }

  static String users() {
    return "{\"users\":[" + user("prn_1", "ann@example.com", "Ann", null, "orders:clerk", "billing:admin") + ","
        + user("prn_2", "bob@example.com", "Bob", "PENDING", "orders:admin") + "],\"total\":5,\"page\":0,\"pageSize\":2}";
  }

  static String invitations() {
    return "{\"invitations\":[{\"userId\":\"prn_2\",\"email\":\"bob@example.com\",\"name\":\"Bob\",\"clientId\":\"clt_1\","
        + "\"roles\":[\"orders:admin\",\"billing:x\"],\"status\":\"PENDING\",\"createdAt\":\"2026-09-01T08:00:00.000Z\","
        + "\"expiresAt\":\"2026-10-20T10:00:00.000Z\",\"inviteLink\":\"" + LINK + "\"},"
        + "{\"userId\":\"prn_3\",\"email\":\"cy@example.com\",\"name\":\"Cy\",\"clientId\":\"clt_1\",\"roles\":[\"orders:clerk\"],"
        + "\"status\":\"EXPIRED\",\"createdAt\":\"2026-08-01T08:00:00.000Z\"}],\"total\":2,\"page\":0,\"pageSize\":50}";
  }

  static final String INVITE_BODY =
      "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[\"orders:clerk\"],\"returnInviteLink\":true}";

  TestResponse get(String path, String perms) {
    return client.get(path).header("X-Perms", perms).send();
  }

  TestResponse send(String method, String path, String perms, String body) {
    TestClient.Call call = client.request(io.flowcatalyst.framework.http.Method.valueOf(method), path).header("X-Perms", perms);
    if (body != null) {
      call.jsonBody(body);
    }
    return call.send();
  }

  List<FakePlatform.Recorded> platformCalls() {
    return platform.requests().stream().filter(r -> r.path().startsWith("/api/")).toList();
  }

  // ---- users ---------------------------------------------------------------------------------

  @Test
  void usersAreListedWithTheApplicationsRolesOnlyAndNothingElseThePlatformSent() {
    TestResponse r = get("/api/admin/users?limit=2", VIEW);

    assertThat(r.status()).isEqualTo(200);
    JsonNode items = r.tree().get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("roles")).extracting(JsonNode::asString).containsExactly("orders:clerk");
    assertThat(items.get(1).get("invitation").asString()).isEqualTo("PENDING");
    assertThat(items.get(0).get("lastLoginAt").asString()).isEqualTo("2026-10-01T08:00:00.000Z");
    assertThat(r.tree().get("nextCursor").asString()).isEqualTo("1");
    // no field the wire type does not name: a credential the platform should never send is not copied
    assertThat(r.body()).doesNotContain("LEAK").doesNotContain("passwordHash").doesNotContain("SECRET-LINK").doesNotContain("inviteLink").doesNotContain("billing");
    FakePlatform.Recorded read = platformCalls().getFirst();
    assertThat(read.path()).isEqualTo(USERS);
    assertThat(read.query()).contains("page=0").contains("pageSize=2");
    assertThat(read.headers().get("authorization")).startsWith("Bearer ");
  }

  @Test
  void aUserListPagesByTheNumberOfThePage() {
    TestResponse second = get("/api/admin/users?limit=2&after=1", VIEW);
    assertThat(platformCalls().getFirst().query()).contains("page=1").contains("pageSize=2");
    assertThat(second.tree().get("nextCursor").asString()).isEqualTo("2");
    // 5 users in pages of 2: page 2 is the last
    assertThat(get("/api/admin/users?limit=2&after=2", VIEW).tree().has("nextCursor")).isFalse();
    // the last page: (page + 1) * pageSize >= total has no cursor
    platform.respondTo("GET", USERS, 200, users().replace("\"total\":5", "\"total\":4"));
    assertThat(get("/api/admin/users?limit=2&after=1", VIEW).tree().has("nextCursor")).isFalse();
    assertThat(get("/api/admin/users?after=minus", VIEW).status()).isEqualTo(400);
    assertThat(get("/api/admin/users?after=-1", VIEW).tree().get("error").asString()).isEqualTo("INVALID_CURSOR");
  }

  @Test
  void userFiltersAreSentAndOnlyTheApplicationsRolesNarrowTheList() {
    get("/api/admin/users?q=ann&active=false&clientId=clt_1&roles=orders:clerk,billing:admin,viewer", VIEW);

    String query = platformCalls().getFirst().query();
    assertThat(query).contains("q=ann").contains("active=false").contains("clientId=clt_1");
    assertThat(java.net.URLDecoder.decode(query, java.nio.charset.StandardCharsets.UTF_8)).contains("roles=orders:clerk,viewer").doesNotContain("billing");

    platform.clearRequests();
    TestResponse foreignOnly = get("/api/admin/users?roles=billing:admin", VIEW);
    assertThat(foreignOnly.status()).isEqualTo(200);
    assertThat(foreignOnly.tree().get("items")).isEmpty();
    assertThat(platformCalls()).as("a foreign role matches nothing and asks nothing").isEmpty();
  }

  @Test
  void aUserIsReadByIdAndAnIdThatCouldChangeThePathIsA404WithoutACall() {
    TestResponse one = get("/api/admin/users/prn_1", VIEW);
    assertThat(one.status()).isEqualTo(200);
    assertThat(one.tree().get("email").asString()).isEqualTo("ann@example.com");
    assertThat(one.body()).doesNotContain("LEAK").doesNotContain("billing");

    platform.clearRequests();
    for (String bad : List.of("..%2F..%2Fother%2Fusers%2Fprn_1", "prn_1%3Fx", "a.b", "x%20y")) {
      TestResponse r = get("/api/admin/users/" + bad, VIEW);
      assertThat(r.status()).as(bad).isEqualTo(404);
      assertThat(r.tree().get("error").asString()).isEqualTo("USER_NOT_FOUND");
    }
    assertThat(platformCalls()).isEmpty();

    platform.respondTo("GET", USERS + "/prn_404", 404, "{\"error\":\"User_NOT_FOUND\",\"message\":\"no\"}");
    assertThat(get("/api/admin/users/prn_404", VIEW).tree().get("error").asString()).isEqualTo("USER_NOT_FOUND");
  }

  @Test
  void everyPlatformPathNamesThisApplicationNeverAnotherOne() {
    get("/api/admin/users", VIEW);
    get("/api/admin/users/prn_1", VIEW);
    get("/api/admin/invitations", VIEW);
    send("POST", "/api/admin/users", INVITE, INVITE_BODY);
    send("POST", "/api/admin/invitations/prn_2/resend", RESEND, "{}");
    send("DELETE", "/api/admin/invitations/prn_2", REVOKE, null);

    assertThat(platformCalls()).hasSize(6).allMatch(c -> c.path().startsWith("/api/applications/orders/"));
  }

  // ---- inviting ------------------------------------------------------------------------------

  @Test
  void anInvitationIsSentWithTheRolesAndAnswersCreatedWithTheLinkOnceAndNotCached() {
    TestResponse r = send("POST", "/api/admin/users", INVITE, INVITE_BODY);

    assertThat(r.status()).isEqualTo(201);
    assertThat(r.tree().get("id").asString()).isEqualTo("prn_9");
    assertThat(r.tree().get("inviteLink").asString()).isEqualTo(LINK);
    assertThat(r.tree().get("invitationSent").asBoolean()).isFalse();
    assertThat(r.header("Cache-Control")).contains("no-store");
    JsonNode sent = platformCalls().getFirst().json();
    assertThat(sent.get("email").asString()).isEqualTo("dee@example.com");
    assertThat(sent.get("clientId").asString()).isEqualTo("acme");
    assertThat(sent.get("roles")).extracting(JsonNode::asString).containsExactly("orders:clerk");
    assertThat(sent.get("returnInviteLink").asBoolean()).isTrue();
    assertThat(sent.get("sendInvitation").asBoolean()).isTrue();
  }

  @Test
  void theLinkIsInTheAnswerOnlyWhenAskedForWhateverThePlatformSends() {
    String emailed = "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[\"orders:clerk\"]}";
    // the platform (wrongly) sends a link although none was asked for
    TestResponse r = send("POST", "/api/admin/users", INVITE, emailed);

    assertThat(r.status()).isEqualTo(201);
    assertThat(r.body()).doesNotContain("SECRET-LINK").doesNotContain("inviteLink");
    assertThat(platformCalls().getFirst().json().get("returnInviteLink").asBoolean()).isFalse();

    TestResponse resent = send("POST", "/api/admin/invitations/prn_2/resend", RESEND, "{}");
    assertThat(resent.body()).doesNotContain("SECRET-LINK").doesNotContain("inviteLink");
    assertThat(resent.tree().get("expiresAt").asString()).isEqualTo("2026-10-20T10:00:00.000Z");
  }

  @Test
  void anInviteLinkIsInNoLogAndInNoListAndNotInAnyErrorAnswer() {
    try (LogCapture logs = LogCapture.start()) {
      assertThat(send("POST", "/api/admin/users", INVITE, INVITE_BODY).body()).contains(LINK);
      assertThat(send("POST", "/api/admin/invitations/prn_2/resend", RESEND, "{\"returnInviteLink\":true}").body()).contains(LINK_2);
      // failures after the platform returned a link (a broken answer) and the lists
      platform.respondTo("POST", USERS, 500, "{\"error\":\"BOOM\",\"message\":\"" + LINK + "\"}");
      TestResponse failed = send("POST", "/api/admin/users", INVITE, INVITE_BODY);
      assertThat(failed.body()).doesNotContain("SECRET-LINK");
      assertThat(get("/api/admin/invitations", VIEW).body()).doesNotContain("SECRET-LINK").doesNotContain("inviteLink");
      assertThat(get("/api/admin/users", VIEW).body()).doesNotContain("SECRET-LINK");

      assertThat(logs.text()).doesNotContain("SECRET-LINK").doesNotContain("set-password").doesNotContain("token=");
    }
  }

  @Test
  void aForeignApplicationsRoleIsRefusedHereAndTheShortNameIsLeftToThePlatform() {
    String foreign = "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[\"orders:clerk\",\"billing:admin\"]}";
    TestResponse r = send("POST", "/api/admin/users", INVITE, foreign);

    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().get("error").asString()).isEqualTo("ROLE_APP_FORBIDDEN");
    assertThat(platformCalls()).isEmpty();

    String shortName = "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[\"clerk\"]}";
    assertThat(send("POST", "/api/admin/users", INVITE, shortName).status()).isEqualTo(201);
    assertThat(platformCalls().getFirst().json().get("roles").get(0).asString()).isEqualTo("clerk");
  }

  @Test
  void aBadInvitationIsRefusedBeforeThePlatformIsAsked() {
    for (String body :
        List.of(
            "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[]}",
            "{\"email\":\"not-an-email\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[\"orders:clerk\"]}",
            "{\"email\":\"dee@example.com\",\"name\":\" \",\"clientId\":\"acme\",\"roles\":[\"orders:clerk\"]}",
            "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"\",\"roles\":[\"orders:clerk\"]}",
            "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[\"\"]}",
            "{\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":[\"orders:clerk\"]}")) {
      assertThat(send("POST", "/api/admin/users", INVITE, body).status()).as(body).isEqualTo(400);
    }
    assertThat(platformCalls()).isEmpty();
  }

  @Test
  void eachOfThePlatformsReasonsReachesThePersonInItsOwnWords() {
    record Case(int platformStatus, String platformCode, String message, int status, String code) {}
    for (Case c :
        List.of(
            new Case(403, "ROLE_APP_FORBIDDEN", "role 'billing:admin' belongs to another application", 403, "ROLE_APP_FORBIDDEN"),
            new Case(400, "UNKNOWN_ROLE", "'orders:ghost' is not a role", 400, "UNKNOWN_ROLE"),
            new Case(400, "ROLES_REQUIRED", "at least one role of orders is required", 400, "ROLES_REQUIRED"),
            new Case(409, "EMAIL_EXISTS", "a user with this email already exists", 409, "EMAIL_EXISTS"),
            new Case(400, "INVITATION_DELIVERY_REQUIRED", "a resend needs sendInvitation or returnInviteLink", 400, "INVITATION_DELIVERY_REQUIRED"),
            new Case(403, "FORBIDDEN", "client is not available to this application", 403, "CLIENT_NOT_AVAILABLE"),
            new Case(500, "INVITE_DELIVERY_FAILED", "the invitation could not be delivered; try again", 502, "INVITE_DELIVERY_FAILED"),
            new Case(400, "CLIENT_REQUIRED", "clientId is required", 400, "CLIENT_REQUIRED"))) {
      platform.respondTo("POST", USERS, c.platformStatus(), "{\"error\":\"" + c.platformCode() + "\",\"message\":\"" + c.message() + "\"}");
      TestResponse r = send("POST", "/api/admin/users", INVITE, INVITE_BODY);
      assertThat(r.status()).as(c.platformCode()).isEqualTo(c.status());
      assertThat(r.tree().get("error").asString()).as(c.platformCode()).isEqualTo(c.code());
      assertThat(r.tree().get("message").asString()).as(c.platformCode()).isEqualTo(c.message());
    }
  }

  @Test
  void aPlatformFailureWithoutAReasonForThePersonIsA502WithoutItsText() {
    platform.respondTo("POST", USERS, 500, "{\"error\":\"INTERNAL\",\"message\":\"db at internal-host-7 down\"}");
    TestResponse r = send("POST", "/api/admin/users", INVITE, INVITE_BODY);
    assertThat(r.status()).isEqualTo(502);
    assertThat(r.tree().get("error").asString()).isEqualTo("PLATFORM_ERROR");
    assertThat(r.body()).doesNotContain("internal-host-7");

    // the service account lacks the platform's permission: said as that, for a change
    platform.respondTo("POST", USERS, 403, "{\"error\":\"FORBIDDEN\",\"message\":\"internal-host-8 says no\"}");
    TestResponse denied = send("POST", "/api/admin/users", INVITE, INVITE_BODY);
    assertThat(denied.status()).isEqualTo(502);
    assertThat(denied.tree().get("message").asString()).contains("service account").contains("manage");
    assertThat(denied.body()).doesNotContain("internal-host-8");
  }

  // ---- invitations ---------------------------------------------------------------------------

  @Test
  void invitationsAreListedWithoutTheLinkAndWithTheApplicationsRolesOnly() {
    TestResponse r = get("/api/admin/invitations?status=PENDING&limit=1", VIEW);

    assertThat(r.status()).isEqualTo(200);
    assertThat(r.tree().get("items").get(0).get("status").asString()).isEqualTo("PENDING");
    assertThat(r.tree().get("items").get(0).get("roles")).extracting(JsonNode::asString).containsExactly("orders:admin");
    assertThat(r.tree().get("items").get(1).get("status").asString()).isEqualTo("EXPIRED");
    assertThat(r.tree().get("items").get(1).has("expiresAt")).isFalse();
    assertThat(r.body()).doesNotContain("SECRET-LINK").doesNotContain("inviteLink").doesNotContain("billing");
    assertThat(platformCalls().getFirst().query()).contains("status=PENDING").contains("pageSize=1");
    assertThat(get("/api/admin/invitations?status=NOPE", VIEW).status()).isEqualTo(400);
  }

  @Test
  void aResendAsksForTheLinkOrTheEmailAndAnUnknownInvitationIsA404() {
    send("POST", "/api/admin/invitations/prn_2/resend", RESEND, "{\"returnInviteLink\":true}");
    JsonNode asLink = platformCalls().getFirst().json();
    assertThat(asLink.get("returnInviteLink").asBoolean()).isTrue();
    assertThat(asLink.get("sendInvitation").asBoolean()).isFalse();

    platform.clearRequests();
    send("POST", "/api/admin/invitations/prn_2/resend", RESEND, "{}");
    JsonNode asEmail = platformCalls().getFirst().json();
    assertThat(asEmail.get("returnInviteLink").asBoolean()).isFalse();
    assertThat(asEmail.get("sendInvitation").asBoolean()).isTrue();

    platform.respondTo("POST", INVITATIONS + "/prn_none/resend", 404, "{\"error\":\"Invitation_NOT_FOUND\",\"message\":\"x\"}");
    TestResponse missing = send("POST", "/api/admin/invitations/prn_none/resend", RESEND, "{}");
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.tree().get("error").asString()).isEqualTo("INVITATION_NOT_FOUND");

    platform.respondTo("POST", INVITATIONS + "/prn_2/resend", 500, "{\"error\":\"INVITE_DELIVERY_FAILED\",\"message\":\"the invitation could not be delivered; try again\"}");
    TestResponse failed = send("POST", "/api/admin/invitations/prn_2/resend", RESEND, "{}");
    assertThat(failed.status()).isEqualTo(502);
    assertThat(failed.tree().get("message").asString()).isEqualTo("the invitation could not be delivered; try again");
  }

  @Test
  void aRevokeAnswers204AndNeverBuildsAPathFromWhatTheCallerTyped() {
    TestResponse r = send("DELETE", "/api/admin/invitations/prn_2", REVOKE, null);
    assertThat(r.status()).isEqualTo(204);
    assertThat(platformCalls().getFirst().method()).isEqualTo("DELETE");

    platform.clearRequests();
    assertThat(send("DELETE", "/api/admin/invitations/..%2Fusers%2Fprn_1", REVOKE, null).status()).isEqualTo(404);
    assertThat(platformCalls()).isEmpty();

    platform.respondTo("DELETE", INVITATIONS + "/prn_none", 404, "{\"error\":\"Invitation_NOT_FOUND\"}");
    assertThat(send("DELETE", "/api/admin/invitations/prn_none", REVOKE, null).tree().get("error").asString()).isEqualTo("INVITATION_NOT_FOUND");
  }

  // ---- the gate ------------------------------------------------------------------------------

  @Test
  void everyOperationNeedsItsOwnPermissionAndAWrongOneNeverReachesThePlatform() {
    record Op(String method, String path, String body, String needs) {}
    List<Op> ops =
        List.of(
            new Op("GET", "/api/admin/users", null, VIEW),
            new Op("GET", "/api/admin/users/prn_1", null, VIEW),
            new Op("GET", "/api/admin/invitations", null, VIEW),
            new Op("POST", "/api/admin/users", INVITE_BODY, INVITE),
            new Op("POST", "/api/admin/invitations/prn_2/resend", "{}", RESEND),
            new Op("DELETE", "/api/admin/invitations/prn_2", null, REVOKE));
    List<String> all = List.of(VIEW, INVITE, RESEND, REVOKE, "orders:admin:console:view", "orders:admin:roles:view", "orders:admin:activity:view");
    for (Op op : ops) {
      for (String held : all) {
        if (held.equals(op.needs())) {
          continue;
        }
        TestResponse r = send(op.method(), op.path(), held, op.body());
        assertThat(r.status()).as(op.method() + " " + op.path() + " holding " + held).isEqualTo(403);
        assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
        assertThat(r.tree().get("message").asString()).isEqualTo("this needs the permission " + op.needs());
      }
      assertThat(send(op.method(), op.path(), "", op.body()).status()).as(op.path() + " with nothing").isEqualTo(403);
      assertThat(send(op.method(), op.path(), "orders:admin:*:*", op.body()).status()).as(op.path() + " by wildcard").isLessThan(300);
      assertThat(send(op.method(), op.path(), op.needs(), op.body()).status()).as(op.path() + " with its own").isLessThan(300);
    }
    // only the calls that were allowed reached the platform: 2 per operation
    assertThat(platformCalls()).hasSize(ops.size() * 2);
  }

  @Test
  void signedOutIs401AndTheNavigationListsTheSectionOnlyForItsViewers() {
    assertThat(client.get("/api/admin/users").send().status()).isEqualTo(401);
    JsonNode nav = get("/api/admin/navigation", "orders:admin:console:view," + VIEW).tree();
    assertThat(nav.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about", "users");
    JsonNode none = get("/api/admin/navigation", "orders:admin:console:view," + INVITE).tree();
    assertThat(none.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about");
  }

  @Test
  void withoutAPlatformConnectionTheAnswerIs503() {
    app.close();
    app = starting(Map.of("FLOWCATALYST_ENABLED", "false"));
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));

    assertThat(get("/api/admin/users", VIEW).status()).isEqualTo(503);
    assertThat(send("POST", "/api/admin/users", INVITE, INVITE_BODY).tree().get("error").asString()).isEqualTo("PLATFORM_NOT_CONFIGURED");
  }

  @Test
  void aTokenThatTheFirstCallRefusedIsFetchedAgainOnce() {
    int before = platform.tokenRequests();
    get("/api/admin/users", VIEW);
    assertThat(platform.tokenRequests()).isEqualTo(before + 1);
    // a 401 from the platform means the cached token was revoked: one new token, one retry
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    platform.route("GET", USERS, request -> calls.incrementAndGet() == 1 ? new FakePlatform.Reply(401, "{}") : new FakePlatform.Reply(200, users()));
    assertThat(get("/api/admin/users", VIEW).status()).isEqualTo(200);
    assertThat(calls.get()).isEqualTo(2);
  }
}
