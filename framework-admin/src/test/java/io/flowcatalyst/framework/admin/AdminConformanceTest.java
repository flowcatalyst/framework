package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.TestResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes (or, without {@code -Dconformance.update=true}, compares) {@code
 * conformance/admin/navigation.json}: for a fixed set of sections, the answer of {@code GET
 * /api/admin/navigation} per permission set (admin contract section 8). The Go side compares.
 */
class AdminConformanceTest {

  static final String DESCRIPTION =
      "GET /api/admin/navigation (admin contract 4.1 and section 3). Setup: application 'acme' with the admin module"
          + " (its own section 'about', permission acme:admin:console:view, first) and the sections below, registered in"
          + " this order after it. A caller gets 200 with the ids of the sections whose permission its held permissions"
          + " grant (the platform's wildcard rule: same number of ':'-separated segments, '*' in a held segment matches),"
          + " in registration order, provided it holds the console permission; otherwise 403 with error code FORBIDDEN."
          + " A section's own permission never opens the console. 'sectionsDetail' gives the full body for the cases that"
          + " name it. Absent optional fields (icon, description) are omitted, not null.";

  record Case(String name, List<String> held) {}

  static final List<Case> CASES =
      List.of(
          new Case("no permissions", List.of()),
          new Case("another section's permission alone", List.of("acme:admin:things:view")),
          new Case("console alone", List.of("acme:admin:console:view")),
          new Case("console and users", List.of("acme:admin:console:view", "acme:admin:users:view")),
          new Case("console, things and users (order is the registry's)", List.of("acme:admin:users:view", "acme:admin:console:view", "acme:admin:things:view")),
          new Case("every admin permission by wildcard", List.of("acme:admin:*:*")),
          new Case("every view by wildcard", List.of("acme:admin:*:view")),
          new Case("every manage by wildcard", List.of("acme:admin:*:manage")),
          new Case("a wildcard of another application", List.of("other:admin:*:*")),
          new Case("a wildcard with fewer segments", List.of("acme:admin:*")),
          new Case("a wildcard with more segments", List.of("acme:admin:*:*:*")),
          new Case("a different case", List.of("ACME:admin:console:view")));

  @Test
  void theNavigationVectorIsCurrent() throws Exception {
    JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    Map<String, Object> vector = new LinkedHashMap<>();
    vector.put("description", DESCRIPTION);
    vector.put("application", "acme");
    List<Map<String, Object>> sections = new ArrayList<>();
    for (AdminSection s : List.of(AdminFixture.THINGS, AdminFixture.USERS)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", s.id());
      m.put("title", s.title());
      m.put("path", s.path());
      m.put("permission", s.permission());
      if (s.icon() != null) {
        m.put("icon", s.icon());
      }
      if (s.description() != null) {
        m.put("description", s.description());
      }
      sections.add(m);
    }
    vector.put("sections", sections);

    List<Map<String, Object>> cases = new ArrayList<>();
    try (App app = AdminFixture.start(AdminFixture.sections(AdminFixture.THINGS, AdminFixture.USERS))) {
      for (Case c : CASES) {
        TestResponse r = AdminFixture.client(app).get("/api/admin/navigation").header("X-Perms", String.join(",", c.held())).send();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", c.name());
        entry.put("permissions", c.held());
        Map<String, Object> expect = new LinkedHashMap<>();
        expect.put("status", r.status());
        JsonNode tree = r.tree();
        if (r.status() == 200) {
          List<String> ids = new ArrayList<>();
          for (JsonNode s : tree.get("sections")) {
            ids.add(s.get("id").asString());
          }
          expect.put("sections", ids);
          if (ids.size() == 3) {
            expect.put("sectionsDetail", mapper.readValue(tree.get("sections").toString(), List.class));
          }
        } else {
          expect.put("error", tree.get("error").asString());
        }
        entry.put("expect", expect);
        cases.add(entry);
      }
    }
    vector.put("cases", cases);

    Conformance.check("admin/navigation.json", mapper.writeValueAsString(vector) + "\n");
  }
}
