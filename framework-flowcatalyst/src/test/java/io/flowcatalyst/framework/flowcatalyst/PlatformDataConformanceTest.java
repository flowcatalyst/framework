package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes (or, without {@code -Dconformance.update=true}, compares) {@code conformance/admin/users.json}
 * and {@code conformance/admin/activity.json}: what the users and activity sections ask of the
 * platform and answer, for given platform answers (admin contract section 12). The Go side compares.
 */
class PlatformDataConformanceTest {

  static final String USERS_DESCRIPTION =
      "The admin users section (admin contract section 12): GET /api/admin/users[/{id}], GET /api/admin/invitations, POST"
          + " /api/admin/users, POST /api/admin/invitations/{id}/resend, DELETE /api/admin/invitations/{id}. Setup: application"
          + " 'orders' (flowcatalyst.application); the platform answers the routes in 'platform' ('METHOD path' to {status, body})"
          + " unless a case has 'override' entries of the same shape. The service account is the caller on the platform; the"
          + " signed-in user's permissions decide first (users:view, :invite, :resend, :revoke; a wildcard grant counts): without"
          + " the operation's permission the answer is 403 FORBIDDEN and the platform is not called. 'calls' lists the platform"
          + " requests in order as 'METHOD path' (the application code in a path is always 'orders'); 'platformBody' is the JSON"
          + " the first call sent. Lists page by cursor = the next platform page number (nextCursor present when (page + 1) *"
          + " limit < total); roles shown are the application's own ('orders:'-prefixed); a role filter keeps only own roles and"
          + " asks nothing when none is left; an id outside [A-Za-z0-9_-]{1,64} is 404 without a call; invite refuses another"
          + " application's role (403 ROLE_APP_FORBIDDEN) before the platform. The invite link is a credential: 'answerHasLink' is"
          + " true only when the caller asked for it (returnInviteLink), whatever the platform sends, and no list carries it."
          + " A platform answer of 400/409/422, or 403/500 with a reason of its own, is passed on in the platform's words"
          + " (status, code, message; 500 becomes 502; a 403 FORBIDDEN 'client is not available to this application' becomes"
          + " CLIENT_NOT_AVAILABLE); any other failure is 502 PLATFORM_ERROR with a message that names no host, token or"
          + " platform text; a platform 404 of a single record is 404 USER_NOT_FOUND / INVITATION_NOT_FOUND.";

  static final String ACTIVITY_DESCRIPTION =
      "The admin activity section (admin contract section 12): GET /api/admin/events[/{id}] and GET /api/admin/dispatch-jobs[/{id}],"
          + " permission activity:view. Setup as for users.json (platform routes are 'GET path'). The platform is asked with"
          + " applications=orders; lists ask for limit + 1 rows and page by offset (nextCursor = offset + limit when more rows"
          + " came back); only events whose type starts with 'orders:' and jobs whose code's first segment is 'orders' are kept"
          + " (a list keeps them, by id anything else is 404 EVENT_NOT_FOUND / DISPATCH_JOB_NOT_FOUND and, for a job, its"
          + " attempts are not asked); a type/code filter of another application is an empty page without a call; an event"
          + " detail lists this application's jobs for it; targetUrl is shown without user info, query and fragment; an event"
          + " payload is cut at 20000 characters and an attempt's responseBody at 4000 ('...Truncated' says so). 'calls' and"
          + " the failure rules are as in users.json.";

  @TempDir Path dir;

  record Case(String name, String method, String path, List<String> held, String body, Map<String, String[]> override) {
    static Case of(String name, String method, String path, String body, String... held) {
      return new Case(name, method, path, List.of(held), body, Map.of());
    }

    Case overriding(String route, int status, String platformBody) {
      Map<String, String[]> more = new LinkedHashMap<>(override);
      more.put(route, new String[] {String.valueOf(status), platformBody});
      return new Case(name, method, path, held, body, more);
    }
  }

  static final String VIEW = PlatformUsersAdminTest.VIEW;
  static final String INVITE = PlatformUsersAdminTest.INVITE;
  static final String RESEND = PlatformUsersAdminTest.RESEND;
  static final String REVOKE = PlatformUsersAdminTest.REVOKE;
  static final String U = "/api/applications/orders/users";
  static final String I = "/api/applications/orders/invitations";

  static String invite(String roles, boolean link) {
    return "{\"email\":\"dee@example.com\",\"name\":\"Dee\",\"clientId\":\"acme\",\"roles\":" + roles + (link ? ",\"returnInviteLink\":true" : "") + "}";
  }

  static List<Case> userCases() {
    return List.of(
        Case.of("users, a page", "GET", "/api/admin/users?limit=2", null, VIEW),
        Case.of("users, a later page", "GET", "/api/admin/users?limit=2&after=1", null, VIEW),
        Case.of("users, the last page has no cursor", "GET", "/api/admin/users?limit=2&after=2", null, VIEW),
        Case.of("users, a bad cursor", "GET", "/api/admin/users?after=x", null, VIEW),
        Case.of("users, filters", "GET", "/api/admin/users?q=ann&active=true&clientId=clt_1&roles=orders:clerk", null, VIEW),
        Case.of("users, a foreign role filter asks nothing", "GET", "/api/admin/users?roles=billing:admin", null, VIEW),
        Case.of("a user", "GET", "/api/admin/users/prn_1", null, VIEW),
        Case.of("a user id that could change the path", "GET", "/api/admin/users/a.b", null, VIEW),
        Case.of("a user the platform does not know", "GET", "/api/admin/users/prn_404", null, VIEW).overriding("GET " + U + "/prn_404", 404, "{\"error\":\"User_NOT_FOUND\"}"),
        Case.of("users, no permission", "GET", "/api/admin/users", null),
        Case.of("users, the invite permission is not enough", "GET", "/api/admin/users", null, INVITE),
        Case.of("users, the platform refuses the service account", "GET", "/api/admin/users", null, VIEW).overriding("GET " + U, 403, "{\"error\":\"FORBIDDEN\",\"message\":\"internal\"}"),
        Case.of("invitations, a page", "GET", "/api/admin/invitations?status=PENDING&limit=1", null, VIEW),
        Case.of("invitations, a bad status", "GET", "/api/admin/invitations?status=NOPE", null, VIEW),
        Case.of("invite, the link asked for", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", true), INVITE),
        Case.of("invite, by email (the platform sends a link anyway)", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", false), INVITE),
        Case.of("invite, a short role name is left to the platform", "POST", "/api/admin/users", invite("[\"clerk\"]", false), INVITE),
        Case.of("invite, another application's role", "POST", "/api/admin/users", invite("[\"orders:clerk\",\"billing:admin\"]", false), INVITE),
        Case.of("invite, no roles", "POST", "/api/admin/users", invite("[]", false), INVITE),
        Case.of("invite, no permission", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", false), VIEW),
        Case.of("invite, the email exists", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", false), INVITE).overriding("POST " + U, 409, "{\"error\":\"EMAIL_EXISTS\",\"message\":\"a user with this email already exists\"}"),
        Case.of("invite, the platform refuses the role", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", false), INVITE).overriding("POST " + U, 403, "{\"error\":\"ROLE_APP_FORBIDDEN\",\"message\":\"role belongs to another application\"}"),
        Case.of("invite, the client is not available", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", false), INVITE).overriding("POST " + U, 403, "{\"error\":\"FORBIDDEN\",\"message\":\"client is not available to this application\"}"),
        Case.of("invite, delivery fails", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", false), INVITE).overriding("POST " + U, 500, "{\"error\":\"INVITE_DELIVERY_FAILED\",\"message\":\"the invitation could not be delivered; try again\"}"),
        Case.of("invite, the platform fails", "POST", "/api/admin/users", invite("[\"orders:clerk\"]", false), INVITE).overriding("POST " + U, 500, "{\"error\":\"INTERNAL\",\"message\":\"internal-host-7\"}"),
        Case.of("resend, by email", "POST", "/api/admin/invitations/prn_2/resend", "{}", RESEND),
        Case.of("resend, the link asked for", "POST", "/api/admin/invitations/prn_2/resend", "{\"returnInviteLink\":true}", RESEND),
        Case.of("resend, no permission", "POST", "/api/admin/invitations/prn_2/resend", "{}", INVITE),
        Case.of("resend, unknown", "POST", "/api/admin/invitations/prn_none/resend", "{}", RESEND).overriding("POST " + I + "/prn_none/resend", 404, "{\"error\":\"Invitation_NOT_FOUND\"}"),
        Case.of("resend, delivery fails", "POST", "/api/admin/invitations/prn_2/resend", "{}", RESEND).overriding("POST " + I + "/prn_2/resend", 500, "{\"error\":\"INVITE_DELIVERY_FAILED\",\"message\":\"the invitation could not be delivered; try again\"}"),
        Case.of("revoke", "DELETE", "/api/admin/invitations/prn_2", null, REVOKE),
        Case.of("revoke, by wildcard", "DELETE", "/api/admin/invitations/prn_2", null, "orders:admin:*:*"),
        Case.of("revoke, no permission", "DELETE", "/api/admin/invitations/prn_2", null, RESEND),
        Case.of("revoke, an id that could change the path", "DELETE", "/api/admin/invitations/a.b", null, REVOKE),
        Case.of("revoke, unknown", "DELETE", "/api/admin/invitations/prn_none", null, REVOKE).overriding("DELETE " + I + "/prn_none", 404, "{\"error\":\"Invitation_NOT_FOUND\"}"));
  }

  static final String A = PlatformActivityAdminTest.VIEW;

  static List<Case> activityCases() {
    return List.of(
        Case.of("events, this application's only", "GET", "/api/admin/events", null, A),
        Case.of("events, a page with a next", "GET", "/api/admin/events?limit=2&after=4", null, A),
        Case.of("events, the last page", "GET", "/api/admin/events?limit=3", null, A),
        Case.of("events, filters", "GET", "/api/admin/events?type=orders:sales:order:placed&correlationId=c&since=2026-10-16T08:00:00Z", null, A),
        Case.of("events, another application's type", "GET", "/api/admin/events?type=billing:invoice:invoice:issued", null, A),
        Case.of("events, a bad time", "GET", "/api/admin/events?since=yesterday", null, A),
        Case.of("an event with its jobs", "GET", "/api/admin/events/ev_1", null, A),
        Case.of("another application's event", "GET", "/api/admin/events/ev_other", null, A),
        Case.of("an event id that could change the path", "GET", "/api/admin/events/a.b", null, A),
        Case.of("events, no permission", "GET", "/api/admin/events", null),
        Case.of("events, the users permission is not enough", "GET", "/api/admin/events", null, VIEW),
        Case.of("events, the platform refuses", "GET", "/api/admin/events", null, A).overriding("GET /api/events", 403, "{\"error\":\"FORBIDDEN\"}"),
        Case.of("jobs, this application's only", "GET", "/api/admin/dispatch-jobs", null, A),
        Case.of("jobs, a status", "GET", "/api/admin/dispatch-jobs?status=FAILED", null, A),
        Case.of("jobs, another application's code", "GET", "/api/admin/dispatch-jobs?code=billing:x:y:z", null, A),
        Case.of("jobs, a bad status", "GET", "/api/admin/dispatch-jobs?status=drop%20table", null, A),
        Case.of("a job with its attempts", "GET", "/api/admin/dispatch-jobs/dj_1", null, A),
        Case.of("another application's job", "GET", "/api/admin/dispatch-jobs/dj_x", null, A),
        Case.of("jobs, no permission", "GET", "/api/admin/dispatch-jobs", null),
        Case.of("jobs, the platform fails", "GET", "/api/admin/dispatch-jobs", null, A).overriding("GET /api/dispatch-jobs", 500, "{\"error\":\"boom\"}"));
  }

  @Test
  void theUsersVectorIsCurrent() throws Exception {
    Map<String, Object[]> platform = new LinkedHashMap<>();
    platform.put("GET " + U, new Object[] {200, PlatformUsersAdminTest.users()});
    platform.put("GET " + U + "/prn_1", new Object[] {200, PlatformUsersAdminTest.user("prn_1", "ann@example.com", "Ann", null, "orders:clerk", "billing:admin")});
    platform.put("GET " + I, new Object[] {200, PlatformUsersAdminTest.invitations()});
    platform.put("POST " + U, new Object[] {201, "{\"id\":\"prn_9\",\"inviteLink\":\"" + PlatformUsersAdminTest.LINK + "\",\"invitationSent\":false}"});
    platform.put("POST " + I + "/prn_2/resend", new Object[] {200, "{\"inviteLink\":\"" + PlatformUsersAdminTest.LINK_2 + "\",\"expiresAt\":\"2026-10-20T10:00:00.000Z\"}"});
    platform.put("DELETE " + I + "/prn_2", new Object[] {204, ""});
    write("admin/users.json", USERS_DESCRIPTION, platform, userCases(), "users.json");
  }

  @Test
  void theActivityVectorIsCurrent() throws Exception {
    Map<String, Object[]> platform = new LinkedHashMap<>();
    platform.put("GET /api/events", new Object[] {200, PlatformActivityAdminTest.events()});
    platform.put("GET /api/events/ev_1", new Object[] {200, PlatformActivityAdminTest.eventDetail("ev_1", "orders:sales:order:placed", "{\"orderId\":\"o1\"}")});
    platform.put("GET /api/events/ev_other", new Object[] {200, PlatformActivityAdminTest.eventDetail("ev_other", "billing:invoice:invoice:issued", "{}")});
    platform.put("GET /api/dispatch-jobs", new Object[] {200, PlatformActivityAdminTest.jobs()});
    platform.put(
        "GET /api/dispatch-jobs/event/ev_1",
        new Object[] {200, "[" + PlatformActivityAdminTest.job("dj_1", "ev_1", "orders:sales:order:placed", "COMPLETED") + "," + PlatformActivityAdminTest.job("dj_x", "ev_1", "billing:x:y:z", "PENDING") + "]"});
    platform.put("GET /api/dispatch-jobs/dj_1", new Object[] {200, PlatformActivityAdminTest.job("dj_1", "ev_1", "orders:sales:order:placed", "COMPLETED")});
    platform.put("GET /api/dispatch-jobs/dj_1/attempts", new Object[] {200, PlatformActivityAdminTest.attempts()});
    platform.put("GET /api/dispatch-jobs/dj_x", new Object[] {200, PlatformActivityAdminTest.job("dj_x", "ev_1", "billing:x:y:z", "PENDING")});
    platform.put("GET /api/dispatch-jobs/dj_x/attempts", new Object[] {200, PlatformActivityAdminTest.attempts()});
    write("admin/activity.json", ACTIVITY_DESCRIPTION, platform, activityCases(), "activity.json");
  }

  private void write(String file, String description, Map<String, Object[]> platform, List<Case> cases, String which) throws Exception {
    JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    Map<String, Object> vector = new LinkedHashMap<>();
    vector.put("description", description);
    vector.put("application", "orders");
    Map<String, Object> platformJson = new LinkedHashMap<>();
    platform.forEach((route, answer) -> platformJson.put(route, answerJson(mapper, (int) answer[0], (String) answer[1])));
    vector.put("platform", platformJson);
    List<Map<String, Object>> out = new ArrayList<>();
    try (FakePlatform fake = FakePlatform.start()) {
      Map<String, String> env = new java.util.HashMap<>(Receiving.env(fake));
      AppBuilder b = App.builder().configDirectory(dir).environment(env);
      b.module(PlatformAdminTest.http()).module(AdminModule.of(PlatformUsersAdminTest.APP));
      if (which.equals("users.json")) {
        b.module(PlatformUsersAdminModule.of(PlatformUsersAdminTest.APP));
      } else {
        b.module(PlatformActivityAdminModule.of(PlatformUsersAdminTest.APP));
      }
      b.module(new FlowCatalystModule());
      try (App app = b.start()) {
        TestClient client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
        for (Case c : cases) {
          install(fake, platform, c.override());
          fake.clearRequests();
          TestClient.Call call = client.request(Method.valueOf(c.method()), c.path()).header("X-Perms", String.join(",", c.held()));
          if (c.body() != null) {
            call.jsonBody(c.body());
          }
          TestResponse r = call.send();
          List<FakePlatform.Recorded> calls = fake.requests().stream().filter(x -> x.path().startsWith("/api/")).toList();
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("name", c.name());
          entry.put("method", c.method());
          entry.put("path", c.path());
          entry.put("permissions", c.held());
          if (c.body() != null) {
            entry.put("body", mapper.readTree(c.body()));
          }
          if (!c.override().isEmpty()) {
            Map<String, Object> o = new LinkedHashMap<>();
            c.override().forEach((route, a) -> o.put(route, answerJson(mapper, Integer.parseInt(a[0]), a[1])));
            entry.put("override", o);
          }
          Map<String, Object> expect = new LinkedHashMap<>();
          expect.put("status", r.status());
          List<String> routes = new ArrayList<>();
          for (FakePlatform.Recorded x : calls) {
            routes.add(x.method() + " " + x.path());
          }
          expect.put("calls", routes);
          if (!calls.isEmpty() && !calls.getFirst().body().isEmpty()) {
            expect.put("platformBody", calls.getFirst().json());
          }
          if (r.status() >= 400) {
            JsonNode tree = r.tree();
            expect.put("error", tree.get("error").asString());
            expect.put("message", tree.get("message").asString());
          } else if (r.status() == 204) {
            // nothing to compare
          } else {
            JsonNode tree = r.tree();
            expect.put("answerHasLink", r.body().contains("inviteLink"));
            if (tree.has("items")) {
              List<String> ids = new ArrayList<>();
              for (JsonNode item : tree.get("items")) {
                ids.add(item.has("id") ? item.get("id").asString() : item.get("userId").asString());
              }
              expect.put("items", ids);
              expect.put("nextCursor", tree.has("nextCursor") ? tree.get("nextCursor").asString() : null);
            } else if (tree.has("event")) {
              expect.put("id", tree.get("event").get("id").asString());
              expect.put("dataTruncated", tree.get("dataTruncated").asBoolean());
              List<String> jobs = new ArrayList<>();
              for (JsonNode j : tree.get("dispatchJobs")) {
                jobs.add(j.get("id").asString());
              }
              expect.put("dispatchJobs", jobs);
            } else if (tree.has("job")) {
              expect.put("id", tree.get("job").get("id").asString());
              expect.put("targetUrl", tree.get("job").get("targetUrl").asString());
              List<Object> lengths = new ArrayList<>();
              for (JsonNode a : tree.get("attempts")) {
                lengths.add(a.has("responseBody") ? a.get("responseBody").asString().length() : null);
              }
              expect.put("attemptBodyLengths", lengths);
              List<Boolean> cut = new ArrayList<>();
              for (JsonNode a : tree.get("attempts")) {
                cut.add(a.get("responseBodyTruncated").asBoolean());
              }
              expect.put("attemptBodyTruncated", cut);
            } else if (tree.has("email")) {
              expect.put("id", tree.get("id").asString());
              List<String> roles = new ArrayList<>();
              for (JsonNode role : tree.get("roles")) {
                roles.add(role.asString());
              }
              expect.put("roles", roles);
            } else if (tree.has("invitationSent")) {
              expect.put("id", tree.get("id").asString());
              expect.put("invitationSent", tree.get("invitationSent").asBoolean());
            } else if (tree.has("expiresAt")) {
              expect.put("expiresAt", tree.get("expiresAt").asString());
            }
          }
          entry.put("expect", expect);
          out.add(entry);
        }
      }
    }
    vector.put("cases", out);
    Conformance.check(file, mapper.writeValueAsString(vector) + "\n");
  }

  private static void install(FakePlatform fake, Map<String, Object[]> base, Map<String, String[]> override) {
    java.util.function.BiConsumer<String, Object[]> put =
        (route, answer) -> {
          int space = route.indexOf(' ');
          fake.respondTo(route.substring(0, space), route.substring(space + 1), (int) answer[0], (String) answer[1]);
        };
    base.forEach(put);
    override.forEach((route, a) -> put.accept(route, new Object[] {Integer.parseInt(a[0]), a[1]}));
  }

  private static Map<String, Object> answerJson(JsonMapper mapper, int status, String body) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", status);
    m.put("body", body.isEmpty() ? null : mapper.readTree(body));
    return m;
  }
}
