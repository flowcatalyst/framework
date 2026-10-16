package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.flowcatalyst.FakePlatform;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stateful part of {@link DemoPlatform}: the users and invitations of {@code orders-demo}, and its
 * events and dispatch jobs, as the platform's application-user and scoped-read routes (F20) answer
 * them. Invitations created, resent or revoked change what the lists show. Users, events and jobs of
 * other applications are in the data so the pages must never show them. Nothing here reaches a real
 * platform, and the "links" are made-up addresses on a name that does not exist.
 */
final class DemoDirectory {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Map<FakePlatform, DemoDirectory> BY_PLATFORM = new ConcurrentHashMap<>();
  private static final Pattern USER = Pattern.compile("/api/applications/orders-demo/users/([^/]+)");
  private static final Pattern RESEND = Pattern.compile("/api/applications/orders-demo/invitations/([^/]+)/resend");
  private static final Pattern INVITATION = Pattern.compile("/api/applications/orders-demo/invitations/([^/]+)");
  private static final Pattern EVENT = Pattern.compile("/api/events/([^/]+)");
  private static final Pattern JOB = Pattern.compile("/api/dispatch-jobs/([^/]+)");
  private static final Pattern ATTEMPTS = Pattern.compile("/api/dispatch-jobs/([^/]+)/attempts");
  private static final Pattern BY_EVENT = Pattern.compile("/api/dispatch-jobs/event/([^/]+)");
  private static final List<String> ROLES = List.of("orders-demo:admin", "orders-demo:clerk", "orders-demo:viewer");

  /** A user of the demo's directory. */
  private static final class U {
    String id;
    String email;
    String name;
    boolean active = true;
    String clientId;
    List<String> roles;
    String invitation; // PENDING, EXPIRED or null
    String created;

    U(String id, String email, String name, String clientId, String invitation, String created, String... roles) {
      this.id = id;
      this.email = email;
      this.name = name;
      this.clientId = clientId;
      this.invitation = invitation;
      this.created = created;
      this.roles = new ArrayList<>(List.of(roles));
    }
  }

  private final List<U> users = new ArrayList<>();
  private volatile int refusal; // 0 = normal, else the status every route answers
  private int nextId = 100;

  private DemoDirectory() {
    reset();
  }

  /** The directory of a fake. */
  static DemoDirectory of(FakePlatform platform) {
    return BY_PLATFORM.get(platform);
  }

  /** Installs the routes on a fake. */
  static void install(FakePlatform platform) {
    DemoDirectory d = new DemoDirectory();
    BY_PLATFORM.put(platform, d);
    platform.routeMatching("GET", "/api/applications/orders-demo/users", d::listUsers);
    platform.routeMatching("POST", "/api/applications/orders-demo/users", d::invite);
    platform.routeMatching("GET", "/api/applications/orders-demo/users/[^/]+", d::getUser);
    platform.routeMatching("GET", "/api/applications/orders-demo/invitations", d::listInvitations);
    platform.routeMatching("POST", "/api/applications/orders-demo/invitations/[^/]+/resend", d::resend);
    platform.routeMatching("DELETE", "/api/applications/orders-demo/invitations/[^/]+", d::revoke);
    platform.routeMatching("GET", "/api/events", d::listEvents);
    platform.routeMatching("GET", "/api/events/[^/]+", d::getEvent);
    platform.routeMatching("GET", "/api/dispatch-jobs", d::listJobs);
    platform.routeMatching("GET", "/api/dispatch-jobs/event/[^/]+", d::jobsOfEvent);
    platform.routeMatching("GET", "/api/dispatch-jobs/[^/]+/attempts", d::attempts);
    platform.routeMatching("GET", "/api/dispatch-jobs/[^/]+", d::getJob);
  }

  /** Every route answers this status (0: normal again). */
  void refuse(int status) {
    this.refusal = status;
  }

  /** Back to the starting users and invitations. */
  synchronized void reset() {
    users.clear();
    nextId = 100;
    users.add(new U("prn_ada", "ada@example.test", "Ada Lovelace", "clt_acme", null, "2026-09-01T08:00:00.000Z", "orders-demo:admin", "billing:admin"));
    users.add(new U("prn_grace", "grace@example.test", "Grace Hopper", "clt_acme", null, "2026-09-02T08:00:00.000Z", "orders-demo:clerk"));
    users.add(new U("prn_linus", "linus@example.test", "Linus Torvalds", "clt_acme", "PENDING", "2026-09-03T08:00:00.000Z", "orders-demo:clerk"));
    users.add(new U("prn_margaret", "margaret@example.test", "Margaret Hamilton", "clt_globex", "EXPIRED", "2026-08-03T08:00:00.000Z", "orders-demo:viewer"));
    for (int i = 1; i <= 8; i++) {
      users.add(new U("prn_user" + i, "user" + i + "@example.test", "Demo User " + i, i % 2 == 0 ? "clt_globex" : "clt_acme", null, "2026-09-1" + i % 10 + "T08:00:00.000Z", "orders-demo:viewer"));
    }
    users.get(users.size() - 1).active = false;
  }

  private static FakePlatform.Reply json(int status, String body) {
    return new FakePlatform.Reply(status, body);
  }

  private static FakePlatform.Reply error(int status, String code, String message) {
    return json(status, "{\"error\":\"" + code + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
  }

  private FakePlatform.Reply refused() {
    return refusal == 0 ? null : error(refusal, "FORBIDDEN", "refused on purpose");
  }

  private static Map<String, String> query(FakePlatform.Recorded r) {
    Map<String, String> out = new java.util.HashMap<>();
    for (String pair : r.query().split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        out.put(
            java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
            java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    return out;
  }

  private static String quoted(List<String> values) {
    List<String> out = new ArrayList<>();
    for (String v : values) {
      out.add("\"" + v + "\"");
    }
    return "[" + String.join(",", out) + "]";
  }

  private static String user(U u, boolean ownRolesOnly) {
    return "{\"id\":\"" + u.id + "\",\"email\":\"" + u.email + "\",\"name\":\"" + u.name + "\",\"active\":" + u.active + ",\"clientId\":\"" + u.clientId
        + "\",\"idpType\":\"INTERNAL\",\"roles\":" + quoted(u.roles.stream().filter(r -> !ownRolesOnly || r.startsWith("orders-demo:")).toList())
        + (u.invitation == null ? "" : ",\"invitation\":\"" + u.invitation + "\"")
        + ",\"createdAt\":\"" + u.created + "\"" + (u.invitation == null ? ",\"lastLoginAt\":\"2026-10-10T09:00:00.000Z\"" : "") + "}";
  }

  private synchronized FakePlatform.Reply listUsers(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Map<String, String> q = query(r);
    String needle = q.getOrDefault("q", "").toLowerCase(Locale.ROOT);
    List<String> roles = q.containsKey("roles") ? List.of(q.get("roles").split(",")) : List.of();
    List<U> match = new ArrayList<>();
    for (U u : users) {
      if (!needle.isEmpty() && !(u.name + " " + u.email).toLowerCase(Locale.ROOT).contains(needle)) {
        continue;
      }
      if (q.containsKey("active") && u.active != Boolean.parseBoolean(q.get("active"))) {
        continue;
      }
      if (q.containsKey("clientId") && !u.clientId.equals(q.get("clientId"))) {
        continue;
      }
      if (!roles.isEmpty() && u.roles.stream().noneMatch(roles::contains)) {
        continue;
      }
      match.add(u);
    }
    int size = Integer.parseInt(q.getOrDefault("pageSize", "50"));
    int page = Integer.parseInt(q.getOrDefault("page", "0"));
    List<String> out = new ArrayList<>();
    for (int i = page * size; i < Math.min(match.size(), (page + 1) * size); i++) {
      out.add(user(match.get(i), false));
    }
    return json(200, "{\"users\":[" + String.join(",", out) + "],\"total\":" + match.size() + ",\"page\":" + page + ",\"pageSize\":" + size + "}");
  }

  private synchronized FakePlatform.Reply getUser(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Matcher m = USER.matcher(r.path());
    m.matches();
    for (U u : users) {
      if (u.id.equals(m.group(1))) {
        return json(200, user(u, false));
      }
    }
    return error(404, "User_NOT_FOUND", "User not found");
  }

  private static boolean waiting(U u) {
    return u.active && u.invitation != null;
  }

  private static String invitation(U u) {
    return "{\"userId\":\"" + u.id + "\",\"email\":\"" + u.email + "\",\"name\":\"" + u.name + "\",\"clientId\":\"" + u.clientId + "\",\"roles\":"
        + quoted(u.roles) + ",\"status\":\"" + u.invitation + "\",\"createdAt\":\"" + u.created + "\""
        + ("PENDING".equals(u.invitation) ? ",\"expiresAt\":\"2026-10-30T08:00:00.000Z\"" : "") + "}";
  }

  private synchronized FakePlatform.Reply listInvitations(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Map<String, String> q = query(r);
    List<String> out = new ArrayList<>();
    for (U u : users) {
      if (waiting(u) && (!q.containsKey("status") || q.get("status").equals(u.invitation))) {
        out.add(invitation(u));
      }
    }
    return json(200, "{\"invitations\":[" + String.join(",", out) + "],\"total\":" + out.size() + ",\"page\":0,\"pageSize\":50}");
  }

  private synchronized FakePlatform.Reply invite(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    JsonNode body = r.json();
    List<String> roles = new ArrayList<>();
    for (JsonNode role : body.get("roles")) {
      roles.add(role.asString().contains(":") ? role.asString() : "orders-demo:" + role.asString());
    }
    String clientRef = body.get("clientId").asString();
    String client = clientRef.equals("acme") || clientRef.equals("clt_acme") ? "clt_acme" : clientRef.equals("globex") || clientRef.equals("clt_globex") ? "clt_globex" : null;
    if (client == null) {
      return error(403, "FORBIDDEN", "client is not available to this application");
    }
    if (roles.isEmpty()) {
      return error(400, "ROLES_REQUIRED", "at least one role of orders-demo is required");
    }
    for (String role : roles) {
      if (!role.startsWith("orders-demo:")) {
        return error(403, "ROLE_APP_FORBIDDEN", "role '" + role + "' belongs to another application");
      }
      if (!ROLES.contains(role)) {
        return error(400, "UNKNOWN_ROLE", "'" + role + "' is not a role of orders-demo");
      }
    }
    String email = body.get("email").asString().toLowerCase(Locale.ROOT);
    for (U u : users) {
      if (u.email.equals(email)) {
        return error(409, "EMAIL_EXISTS", "a user with this email already exists");
      }
    }
    U added = new U("prn_new" + nextId++, email, body.get("name").asString(), client, "PENDING", "2026-10-16T08:00:00.000Z", roles.toArray(String[]::new));
    users.add(added);
    boolean link = body.has("returnInviteLink") && body.get("returnInviteLink").asBoolean();
    boolean send = !body.has("sendInvitation") || body.get("sendInvitation").asBoolean();
    return json(
        201,
        "{\"id\":\"" + added.id + "\"" + (link ? ",\"inviteLink\":\"https://platform.invalid/set-password?token=demo-" + added.id + "\"" : "")
            + ",\"invitationSent\":" + (send && !link) + "}");
  }

  private synchronized FakePlatform.Reply resend(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Matcher m = RESEND.matcher(r.path());
    m.matches();
    for (U u : users) {
      if (u.id.equals(m.group(1)) && waiting(u)) {
        JsonNode body = r.json();
        boolean link = body.has("returnInviteLink") && body.get("returnInviteLink").asBoolean();
        boolean send = !body.has("sendInvitation") || body.get("sendInvitation").asBoolean();
        if (!link && !send) {
          return error(400, "INVITATION_DELIVERY_REQUIRED", "a resend needs sendInvitation or returnInviteLink");
        }
        u.invitation = "PENDING";
        return json(200, "{" + (link ? "\"inviteLink\":\"https://platform.invalid/set-password?token=demo-again-" + u.id + "\"," : "") + "\"expiresAt\":\"2026-10-30T08:00:00.000Z\"}");
      }
    }
    return error(404, "Invitation_NOT_FOUND", "Invitation not found");
  }

  private synchronized FakePlatform.Reply revoke(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Matcher m = INVITATION.matcher(r.path());
    m.matches();
    for (U u : users) {
      if (u.id.equals(m.group(1)) && waiting(u)) {
        u.active = false;
        u.invitation = null;
        return json(204, "");
      }
    }
    return error(404, "Invitation_NOT_FOUND", "Invitation not found");
  }

  // ---- events and dispatch jobs --------------------------------------------------------------

  private static final String[][] EVENTS = {
    {"ev_01", "orders-demo:sales:order:placed", "o-1001"},
    {"ev_02", "orders-demo:sales:order:paid", "o-1001"},
    {"ev_03", "orders-demo:sales:order:shipped", "o-1001"},
    {"ev_04", "orders-demo:sales:order:placed", "o-1002"},
    {"ev_05", "orders-demo:sales:order:cancelled", "o-1002"},
    {"ev_06", "orders-demo:stock:item:counted", "sku-1"},
    {"ev_07", "orders-demo:sales:order:placed", "o-1003"},
    {"ev_08", "orders-demo:sales:order:paid", "o-1003"},
    {"ev_09", "orders-demo:sales:order:placed", "o-1004"},
    {"ev_10", "orders-demo:sales:order:placed", "o-1005"},
    {"ev_x1", "billing:invoice:invoice:issued", "inv-1"},
  };

  private static String event(String[] e) {
    String[] p = e[1].split(":");
    int n = Integer.parseInt(e[0].replaceAll("\\D", "0").replaceAll("^0+(?=.)", ""));
    return "{\"id\":\"" + e[0] + "\",\"type\":\"" + e[1] + "\",\"source\":\"" + p[0] + ":" + p[1] + "\",\"subject\":\"" + e[2] + "\",\"time\":\"2026-10-15T10:" + String.format("%02d", n % 60)
        + ":00.000Z\",\"application\":\"" + p[0] + "\",\"subdomain\":\"" + p[1] + "\",\"aggregate\":\"" + p[2] + "\",\"correlationId\":\"corr-" + e[2] + "\"}";
  }

  private FakePlatform.Reply listEvents(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Map<String, String> q = query(r);
    List<String> all = new ArrayList<>();
    for (int i = EVENTS.length - 1; i >= 0; i--) {
      String[] e = EVENTS[i];
      if (q.containsKey("type") && !q.get("type").equals(e[1])) {
        continue;
      }
      if (q.containsKey("subject") && !e[2].contains(q.get("subject"))) {
        continue;
      }
      if (q.containsKey("applications") && !e[1].startsWith(q.get("applications") + ":")) {
        continue;
      }
      all.add(event(e));
    }
    int offset = Integer.parseInt(q.getOrDefault("offset", "0"));
    int limit = Integer.parseInt(q.getOrDefault("limit", "50"));
    return json(200, "[" + String.join(",", all.subList(Math.min(offset, all.size()), Math.min(all.size(), offset + limit))) + "]");
  }

  private FakePlatform.Reply getEvent(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Matcher m = EVENT.matcher(r.path());
    m.matches();
    for (String[] e : EVENTS) {
      if (e[0].equals(m.group(1))) {
        String read = event(e);
        return json(
            200,
            read.substring(0, read.length() - 1) + ",\"data\":{\"orderId\":\"" + e[2] + "\",\"note\":\"demo payload\"},\"contextData\":[{\"key\":\"channel\",\"value\":\"web\"}],\"causationId\":\"cause-" + e[0] + "\"}");
      }
    }
    return error(404, "Event_NOT_FOUND", "Event not found");
  }

  // a job per event, the second event of each order fails once on another application's endpoint
  private static String[] job(String[] e) {
    boolean failed = e[0].equals("ev_02") || e[0].equals("ev_08");
    String status = failed ? "FAILED" : e[0].equals("ev_10") ? "PENDING" : "COMPLETED";
    String id = "dj_" + e[0].substring(3);
    String[] p = e[1].split(":");
    String json =
        "{\"id\":\"" + id + "\",\"eventId\":\"" + e[0] + "\",\"subscriptionId\":\"sub_billing\",\"code\":\"" + e[1] + "\",\"status\":\"" + status
            + "\",\"kind\":\"EVENT\",\"targetUrl\":\"https://billing.example.test/hooks/orders?token=demo-secret\",\"mode\":\"IMMEDIATE\",\"descriptor\":\"Billing sync\","
            + "\"createdAt\":\"2026-10-15T10:30:00.000Z\",\"updatedAt\":\"2026-10-15T10:31:00.000Z\",\"application\":\"" + p[0] + "\",\"attemptCount\":" + (failed ? 2 : 1)
            + ",\"protocol\":\"HTTP_WEBHOOK\",\"timeoutSeconds\":30,\"maxRetries\":3,\"retryStrategy\":\"EXPONENTIAL\""
            + (failed ? ",\"lastError\":\"HTTP 500\"" : "") + "}";
    return new String[] {id, json, e[0], status};
  }

  private FakePlatform.Reply listJobs(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Map<String, String> q = query(r);
    List<String> all = new ArrayList<>();
    for (int i = EVENTS.length - 1; i >= 0; i--) {
      String[] j = job(EVENTS[i]);
      if (q.containsKey("statuses") && !q.get("statuses").contains(j[3])) {
        continue;
      }
      if (q.containsKey("code") && !EVENTS[i][1].equals(q.get("code"))) {
        continue;
      }
      if (q.containsKey("applications") && !EVENTS[i][1].startsWith(q.get("applications") + ":")) {
        continue;
      }
      all.add(j[1]);
    }
    int offset = Integer.parseInt(q.getOrDefault("offset", "0"));
    int limit = Integer.parseInt(q.getOrDefault("limit", "50"));
    return json(200, "[" + String.join(",", all.subList(Math.min(offset, all.size()), Math.min(all.size(), offset + limit))) + "]");
  }

  private FakePlatform.Reply jobsOfEvent(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Matcher m = BY_EVENT.matcher(r.path());
    m.matches();
    List<String> out = new ArrayList<>();
    for (String[] e : EVENTS) {
      if (e[0].equals(m.group(1))) {
        out.add(job(e)[1]);
      }
    }
    return json(200, "[" + String.join(",", out) + "]");
  }

  private FakePlatform.Reply getJob(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Matcher m = JOB.matcher(r.path());
    m.matches();
    for (String[] e : EVENTS) {
      String[] j = job(e);
      if (j[0].equals(m.group(1))) {
        return json(200, j[1]);
      }
    }
    return error(404, "DispatchJob_NOT_FOUND", "DispatchJob not found");
  }

  private FakePlatform.Reply attempts(FakePlatform.Recorded r) {
    FakePlatform.Reply no = refused();
    if (no != null) {
      return no;
    }
    Matcher m = ATTEMPTS.matcher(r.path());
    m.matches();
    for (String[] e : EVENTS) {
      String[] j = job(e);
      if (j[0].equals(m.group(1))) {
        boolean failed = j[3].equals("FAILED");
        String first =
            "{\"attemptNumber\":1,\"attemptedAt\":\"2026-10-15T10:30:01.000Z\",\"completedAt\":\"2026-10-15T10:30:02.000Z\",\"durationMillis\":"
                + (failed ? 912 : 120) + ",\"responseCode\":" + (failed ? 500 : 200) + ",\"responseBody\":\"" + (failed ? "upstream exploded" : "ok")
                + "\",\"success\":" + !failed + (failed ? ",\"errorMessage\":\"HTTP 500\",\"errorType\":\"HTTP_ERROR\"" : "") + "}";
        String second =
            "{\"attemptNumber\":2,\"attemptedAt\":\"2026-10-15T10:31:01.000Z\",\"durationMillis\":30000,\"success\":false,\"errorMessage\":\"timed out\",\"errorType\":\"TIMEOUT\"}";
        return json(200, "[" + first + (failed ? "," + second : "") + "]");
      }
    }
    return error(404, "DispatchJob_NOT_FOUND", "DispatchJob not found");
  }
}
