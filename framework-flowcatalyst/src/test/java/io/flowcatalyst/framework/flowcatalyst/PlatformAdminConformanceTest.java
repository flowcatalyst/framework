package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.http.ContextResolver;
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
 * Writes (or, without {@code -Dconformance.update=true}, compares) {@code
 * conformance/admin/platform-reads.json}: what the event types and roles sections answer for given
 * platform answers (admin contract section 11). The Go side compares.
 */
class PlatformAdminConformanceTest {

  static final String DESCRIPTION =
      "GET /api/admin/event-types, /api/admin/event-types/{id}, /api/admin/roles and /api/admin/roles/{name} (admin contract"
          + " section 11). Setup: application 'orders' (flowcatalyst.application) with the admin module and the platform admin"
          + " module; the platform answers the reads in 'platform' (paths relative to its base; the same answer whatever the"
          + " query, as a real platform that ignores a filter would), unless a case has an 'override' {path, status, body}."
          + " The service account is the caller on the platform; the signed-in user's permissions decide first: without"
          + " the section's permission the answer is 403 FORBIDDEN and the platform is not called. Lists keep only records of"
          + " the application (an event type's 'application', a role named '{application}:...' whose applicationCode is the"
          + " application), apply the status/subdomain/aggregate/source and q filters themselves (q: case-insensitive substring"
          + " of code, name or description / of name, displayName or description), sort ascending by code / name and page"
          + " by cursor: limit (default 50, 1 to 200), nextCursor = the key of the last item when more follow, after = keys"
          + " greater than the cursor. A record of another application is 404 EVENT_TYPE_NOT_FOUND / ROLE_NOT_FOUND (a role"
          + " name outside the application is refused without calling the platform), a 404 from the platform likewise. A"
          + " platform refusal or failure is 502 PLATFORM_ERROR and the message names no host, token or platform text."
          + " Expectations list ids/keys, not full bodies; 'expect.message' is compared where given.";

  @TempDir Path dir;

  record Case(String name, String path, List<String> held, String[] override) {
    static Case of(String name, String path, String... held) {
      return new Case(name, path, List.of(held), null);
    }

    Case overriding(String path, int status, String body) {
      return new Case(name, this.path, held, new String[] {path, String.valueOf(status), body});
    }
  }

  static final String ET = "orders:admin:event-types:view";
  static final String ROLES = "orders:admin:roles:view";

  static List<Case> cases() {
    return List.of(
        Case.of("event types, sorted, this application's only", "/api/admin/event-types", ET),
        Case.of("event types, a page of two", "/api/admin/event-types?limit=2", ET),
        Case.of("event types, the next page", "/api/admin/event-types?limit=2&after=orders:sales:order:shipped", ET),
        Case.of("event types, archived only", "/api/admin/event-types?status=ARCHIVED", ET),
        Case.of("event types, a subdomain and an aggregate", "/api/admin/event-types?subdomain=sales&aggregate=order", ET),
        Case.of("event types, text", "/api/admin/event-types?q=SHIPPED", ET),
        Case.of("event types, limit above the maximum is the maximum", "/api/admin/event-types?limit=1000", ET),
        Case.of("event types, limit below one is one", "/api/admin/event-types?limit=0", ET),
        Case.of("an event type", "/api/admin/event-types/et_1", ET),
        Case.of("another application's event type", "/api/admin/event-types/et_other", ET),
        Case.of("an event type the platform does not know", "/api/admin/event-types/et_none", ET).overriding("/api/event-types/et_none", 404, "{\"error\":\"nope\"}"),
        Case.of("event types, the platform refuses", "/api/admin/event-types", ET).overriding("/api/event-types", 403, "{\"error\":\"forbidden\"}"),
        Case.of("event types, the platform fails", "/api/admin/event-types", ET).overriding("/api/event-types", 500, "{\"error\":\"boom\"}"),
        Case.of("event types, no permission", "/api/admin/event-types"),
        Case.of("event types, the roles permission is not enough", "/api/admin/event-types", ROLES),
        Case.of("event types, by wildcard", "/api/admin/event-types", "orders:admin:*:*"),
        Case.of("roles, this application's only", "/api/admin/roles", ROLES),
        Case.of("roles, by source", "/api/admin/roles?source=CODE", ROLES),
        Case.of("roles, text", "/api/admin/roles?q=clerk", ROLES),
        Case.of("roles, a page of one", "/api/admin/roles?limit=1", ROLES),
        Case.of("roles, the next page", "/api/admin/roles?limit=1&after=orders:admin", ROLES),
        Case.of("a role, permissions sorted", "/api/admin/roles/orders:clerk", ROLES),
        Case.of("another application's role is refused before the platform", "/api/admin/roles/billing:admin", ROLES),
        Case.of("a role the platform does not know", "/api/admin/roles/orders:none", ROLES).overriding("/api/roles/orders:none", 404, "{\"error\":\"nope\"}"),
        Case.of("roles, the platform refuses", "/api/admin/roles", ROLES).overriding("/api/roles", 403, "{\"error\":\"forbidden\"}"),
        Case.of("roles, no permission", "/api/admin/roles"),
        Case.of("roles, the event types permission is not enough", "/api/admin/roles", ET));
  }

  @Test
  void thePlatformReadsVectorIsCurrent() throws Exception {
    JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    Map<String, JsonNode> platformAnswers = new LinkedHashMap<>();
    platformAnswers.put("/api/event-types", mapper.readTree(PlatformAdminTest.eventTypes()));
    platformAnswers.put("/api/event-types/et_1", mapper.readTree(PlatformAdminTest.eventType("et_1", "orders:sales:order:placed", "Order placed", "orders", "CURRENT", true)));
    platformAnswers.put("/api/event-types/et_other", mapper.readTree(PlatformAdminTest.eventType("et_other", "billing:invoice:invoice:issued", "Issued", "billing", "CURRENT", false)));
    platformAnswers.put("/api/roles", mapper.readTree(PlatformAdminTest.roles()));
    platformAnswers.put(
        "/api/roles/orders:clerk",
        mapper.readTree(PlatformAdminTest.role("rol_1", "orders:clerk", "orders", "Clerk", "SDK", "orders:sales:order:write", "orders:sales:order:read")));

    Map<String, Object> vector = new LinkedHashMap<>();
    vector.put("description", DESCRIPTION);
    vector.put("application", "orders");
    vector.put("platform", platformAnswers);
    List<Map<String, Object>> out = new ArrayList<>();
    try (FakePlatform platform = FakePlatform.start()) {
      platformAnswers.forEach((path, body) -> platform.respondGet(path, 200, body.toString()));
      Map<String, String> env = new java.util.HashMap<>(Receiving.env(platform));
      AppBuilder b = App.builder().configDirectory(dir).environment(env);
      b.module(PlatformAdminTest.http()).module(AdminModule.of(PlatformAdminTest.APP)).module(PlatformAdminModule.of(PlatformAdminTest.APP)).module(new FlowCatalystModule());
      try (App app = b.start()) {
        TestClient client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
        for (Case c : cases()) {
          if (c.override() != null) {
            platform.respondGet(c.override()[0], Integer.parseInt(c.override()[1]), c.override()[2]);
          }
          long before = platform.requests().stream().filter(r -> r.path().startsWith("/api/") && !r.path().startsWith("/api/admin")).count();
          TestResponse r = client.get(c.path()).header("X-Perms", String.join(",", c.held())).send();
          long calls = platform.requests().stream().filter(x -> x.path().startsWith("/api/") && !x.path().startsWith("/api/admin")).count() - before;
          if (c.override() != null) {
            platformAnswers.forEach((path, body) -> platform.respondGet(path, 200, body.toString()));
          }
          Map<String, Object> entry = new LinkedHashMap<>();
          entry.put("name", c.name());
          entry.put("path", c.path());
          entry.put("permissions", c.held());
          if (c.override() != null) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("path", c.override()[0]);
            o.put("status", Integer.parseInt(c.override()[1]));
            o.put("body", mapper.readTree(c.override()[2]));
            entry.put("override", o);
          }
          Map<String, Object> expect = new LinkedHashMap<>();
          expect.put("status", r.status());
          expect.put("platformCalled", calls > 0);
          JsonNode tree = r.tree();
          if (r.status() == 200) {
            if (tree.has("items")) {
              List<String> keys = new ArrayList<>();
              for (JsonNode item : tree.get("items")) {
                keys.add(item.has("code") ? item.get("code").asString() : item.get("name").asString());
              }
              expect.put("items", keys);
              expect.put("nextCursor", tree.has("nextCursor") ? tree.get("nextCursor").asString() : null);
            } else if (tree.has("eventType")) {
              expect.put("code", tree.get("eventType").get("code").asString());
              List<String> versions = new ArrayList<>();
              for (JsonNode v : tree.get("versions")) {
                versions.add(v.get("version").asString());
              }
              expect.put("versions", versions);
            } else {
              expect.put("name", tree.get("role").get("name").asString());
              List<String> permissions = new ArrayList<>();
              for (JsonNode p : tree.get("permissions")) {
                permissions.add(p.asString());
              }
              expect.put("permissions", permissions);
            }
          } else {
            expect.put("error", tree.get("error").asString());
            if (r.status() == 502) {
              expect.put("message", tree.get("message").asString());
            }
          }
          entry.put("expect", expect);
          out.add(entry);
        }
      }
    }
    vector.put("cases", out);
    Conformance.check("admin/platform-reads.json", mapper.writeValueAsString(vector) + "\n");
  }
}
