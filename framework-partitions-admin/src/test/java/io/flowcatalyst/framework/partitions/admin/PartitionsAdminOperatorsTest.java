package io.flowcatalyst.framework.partitions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.admin.AdminRegistry;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.OpenApiDocument;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The section is for platform operators only (the Go {@code operators_test.go}): every operation under
 * every tier, the navigation, the OpenAPI marker, the section in an operators-only area, and the gate
 * and refusals of the vector {@code conformance/admin/partitions.json}.
 */
class PartitionsAdminOperatorsTest extends PartitionsAdminTestBase {

  /** An operation of the section: the request that reaches its handler and what an authorised operator gets. */
  record Op(String id, String method, String target, String body, String permission, int ok) {}

  static final List<Op> OPERATIONS =
      List.of(
          new Op("listAdminPartitions", "GET", "/api/admin/partitions", "", "view", 200),
          new Op("getAdminPartitionTable", "GET", "/api/admin/partitions/readings", "", "view", 200),
          new Op("listAdminPartitionArchives", "GET", "/api/admin/partitions/readings/archives", "", "view", 200),
          new Op("runAdminPartitionPass", "POST", "/api/admin/partitions/passes", "{}", "manage", 200),
          new Op("ensureAdminPartitions", "POST", "/api/admin/partitions/readings/ensure", "{\"from\":\"2026-12-01T00:00:00Z\",\"to\":\"2026-12-20T00:00:00Z\"}", "manage", 200));

  static Caller holding(Op o, String tier) {
    return new Caller(List.of("acme:admin:console:view", "acme:admin:partitions:" + o.permission()), tier);
  }

  @Test
  void theTableCoversEveryOperationOfTheSection() throws IOException {
    Rig rig = start(false);
    AdminSection section = rig.app().get(AdminRegistry.class).sections().stream().filter(s -> s.id().equals("partitions")).findFirst().orElseThrow();
    assertThat(section.forOperators()).isTrue();
    assertThat(section.path()).isEqualTo("/admin/partitions");
    assertThat(section.permission()).isEqualTo("acme:admin:partitions:view");
    assertThat(section.title()).isEqualTo("Partitions");
    List<String> ids = new ArrayList<>();
    for (Operation<?, ?> op : section.operations()) {
      ids.add(op.id());
      assertThat(op.operatorsOnly()).as("%s is marked operators-only", op.id()).isTrue();
    }
    assertThat(ids).containsExactlyInAnyOrderElementsOf(OPERATIONS.stream().map(Op::id).toList());
  }

  @Test
  void everyOperationRequiresAPlatformOperatorInAdditionToItsPermission() throws IOException {
    Rig rig = start(false);
    for (Op o : OPERATIONS) {
      // an ANCHOR with the permission
      TestResponse a = send(rig, o.method(), o.target(), o.body(), holding(o, "ANCHOR"));
      assertThat(a.status()).as("%s: an operator with the permission: %s", o.id(), a.body()).isEqualTo(o.ok());
      // the wildcard grant of the suggested admin role works for an operator too
      assertThat(send(rig, o.method(), o.target(), o.body(), OPERATOR_WILD).status()).as("%s with acme:admin:*:*", o.id()).isEqualTo(o.ok());
      // PARTNER, CLIENT, no tier, lower case and another spelling, with every permission the section has
      for (String tier : new String[] {"PARTNER", "CLIENT", null, "anchor", "SERVICE"}) {
        a = send(rig, o.method(), o.target(), o.body(), OPERATOR_WITH_BOTH.withTier(tier));
        assertThat(a.status()).as("%s tier %s with the permission: %s", o.id(), tier, a.body()).isEqualTo(403);
        assertThat(errorCode(a)).isEqualTo("FORBIDDEN");
        assertThat(errorMessage(a)).isEqualTo("this is for platform operators only");
        // even the wildcard does not open it
        assertThat(send(rig, o.method(), o.target(), o.body(), OPERATOR_WILD.withTier(tier)).status()).as("%s tier %s with acme:admin:*:*", o.id(), tier).isEqualTo(403);
      }
      // an ANCHOR without the permission
      a = send(rig, o.method(), o.target(), o.body(), OPERATOR_NO_PERMS);
      assertThat(a.status()).as("%s without the permission", o.id()).isEqualTo(403);
      assertThat(errorCode(a)).isEqualTo("FORBIDDEN");
      assertThat(errorMessage(a)).isEqualTo("this needs the permission acme:admin:partitions:" + o.permission());
      // view does not open manage and the reverse
      String other = o.permission().equals("view") ? "manage" : "view";
      Caller wrong = new Caller(List.of("acme:admin:console:view", "acme:admin:partitions:" + other), "ANCHOR");
      assertThat(send(rig, o.method(), o.target(), o.body(), wrong).status()).as("%s holding %s only", o.id(), other).isEqualTo(403);
      // not signed in
      assertThat(send(rig, o.method(), o.target(), o.body(), NOT_SIGNED_IN).status()).as("%s not signed in", o.id()).isEqualTo(401);
    }
  }

  @Test
  void theNavigationListsTheSectionToOperatorsOnly() throws IOException {
    Rig rig = start(false);
    record Case(String name, Caller caller, List<String> want) {}
    for (Case c :
        List.of(
            new Case("an operator with view", OPERATOR_VIEW_ONLY, List.of("about", "partitions")),
            new Case("an operator with the wildcard", OPERATOR_WILD, List.of("about", "partitions")),
            new Case("an operator without the permission", OPERATOR_NO_PERMS, List.of("about")),
            new Case("a client user with the permission", OPERATOR_WITH_BOTH.withTier("CLIENT"), List.of("about")),
            new Case("a partner with the permission", OPERATOR_WITH_BOTH.withTier("PARTNER"), List.of("about")),
            new Case("no tier with the permission", OPERATOR_WITH_BOTH.withTier(null), List.of("about")),
            new Case("a client user with the wildcard", OPERATOR_WILD.withTier("CLIENT"), List.of("about")))) {
      TestResponse a = send(rig, "GET", "/api/admin/navigation", null, c.caller());
      assertThat(a.status()).as("%s: %s", c.name(), a.body()).isEqualTo(200);
      assertThat(sectionIds(a)).as(c.name()).isEqualTo(c.want());
    }
    TestResponse a = send(rig, "GET", "/api/admin/navigation", null, OPERATOR_VIEW_ONLY);
    assertThat(a.body()).contains("\"path\":\"/admin/partitions\"").contains("\"permission\":\"acme:admin:partitions:view\"");
  }

  /**
   * An application whose admin area holds only the partitions section declares the whole area
   * operators-only (admin contract section 3a, rule 6): the section is accepted there, an operator uses
   * it as before, and nobody else reaches even the navigation.
   */
  @Test
  void thePartitionsSectionInAnOperatorsOnlyArea() throws IOException {
    Rig rig = start(true);
    TestResponse a = send(rig, "GET", "/api/admin/navigation", null, OPERATOR_VIEW_ONLY);
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    assertThat(sectionIds(a)).containsExactly("about", "partitions");
    assertThat(send(rig, "GET", "/api/admin/partitions", null, OPERATOR_VIEW_ONLY).status()).isEqualTo(200);
    for (String tier : new String[] {"CLIENT", "PARTNER", null}) {
      for (String target : new String[] {"/api/admin/navigation", "/api/admin/about", "/api/admin/partitions"}) {
        a = send(rig, "GET", target, null, OPERATOR_WILD.withTier(tier));
        assertThat(a.status()).as("tier %s with every permission, %s: %s", tier, target, a.body()).isEqualTo(403);
        assertThat(errorMessage(a)).isEqualTo("this is for platform operators only");
      }
    }
  }

  @Test
  void theOpenApiDocumentMarksTheOperationsOperatorsOnly() throws IOException {
    String json =
        switch (OpenApi.document(builder(false, newLocks()).declarations())) {
          case OpenApiDocument.Written w -> w.json();
          case OpenApiDocument.Refused r -> throw new AssertionError(r.problems());
        };
    JsonNode paths = JsonMapper.builder().build().readTree(json).get("paths");
    int marked = 0;
    List<String> ids = new ArrayList<>();
    for (var path : paths.properties()) {
      for (var method : path.getValue().properties()) {
        JsonNode op = method.getValue();
        if (op.has("x-fw-operators-only")) {
          assertThat(op.get("x-fw-operators-only").asBoolean()).isTrue();
          marked++;
          ids.add(op.get("operationId").asString());
        }
      }
    }
    assertThat(marked).as("exactly these operations carry x-fw-operators-only: %s", ids).isEqualTo(OPERATIONS.size());
    assertThat(ids).containsExactlyInAnyOrderElementsOf(OPERATIONS.stream().map(Op::id).toList());
    JsonNode tree = JsonMapper.builder().build().readTree(json);
    assertThat(tree.get("paths").get("/api/admin/partitions").get("get").get("x-fw-permission").asString()).isEqualTo("acme:admin:partitions:view");
    assertThat(tree.get("paths").get("/api/admin/partitions/passes").get("post").get("x-fw-permission").asString()).isEqualTo("acme:admin:partitions:manage");
    assertThat(tree.get("components").get("schemas").get("AdminPartitionRetention").get("properties").get("after").get("format").asString()).isEqualTo("duration");
    assertThat(json).contains("PARTITION_TABLE_NOT_FOUND", "PARTITION_PASS_RUNNING", "PARTITION_RANGE_INVALID", "PARTITION_RANGE_TOO_LARGE", "PARTITION_DETACHED_LEFTOVER");
    // the wire types are named as in Go's document
    JsonNode schemas = JsonMapper.builder().build().readTree(json).get("components").get("schemas");
    for (String name :
        new String[] {
          "AdminPartitionKey", "AdminPartitionRetention", "AdminPartitionLastPass", "AdminPartitionArchived", "AdminPartition", "AdminPartitionTableSummary",
          "AdminPartitionTableList", "AdminPartitionTable", "AdminPartitionArchive", "PageAdminPartitionArchive", "AdminPartitionPassBody", "AdminPartitionPassResult",
          "AdminPartitionPassResults", "AdminPartitionEnsureBody", "AdminPartitionEnsureResult"
        }) {
      assertThat(schemas.has(name)).as("schema %s in %s", name, schemas.propertyNames()).isTrue();
    }
  }

  // ---- the vector ----------------------------------------------------------------------------

  @Test
  void theOperationsOfTheVectorAreTheSections() throws IOException {
    JsonNode vector = JsonMapper.builder().build().readTree(Conformance.read("admin/partitions.json"));
    assertThat(vector.get("application").asString()).isEqualTo("acme");
    JsonNode ops = vector.get("operations");
    assertThat(ops).hasSize(OPERATIONS.size());
    Rig rig = start(false);
    AdminSection section = rig.app().get(AdminRegistry.class).sections().stream().filter(s -> s.id().equals("partitions")).findFirst().orElseThrow();
    for (JsonNode vo : ops) {
      Operation<?, ?> found = section.operations().stream().filter(o -> vo.get("id").asString().equals(o.id())).findFirst().orElseThrow();
      assertThat(found.method().name()).isEqualTo(vo.get("method").asString());
      assertThat(found.pattern()).isEqualTo(vo.get("path").asString());
      assertThat(found.permission()).isEqualTo("acme:admin:partitions:" + vo.get("permission").asString());
    }
  }

  @Test
  void theGateVector() throws IOException {
    JsonNode vector = JsonMapper.builder().build().readTree(Conformance.read("admin/partitions.json"));
    JsonNode gate = vector.get("gate");
    assertThat(gate.size()).isPositive();
    Rig rig = start(false);
    for (JsonNode c : gate) {
      String name = c.get("name").asString();
      List<String> perms = new ArrayList<>();
      c.get("permissions").forEach(p -> perms.add(p.asString()));
      Caller who = new Caller(perms, c.has("tier") ? c.get("tier").asString() : null);
      for (JsonNode vo : vector.get("operations")) {
        Op o = OPERATIONS.stream().filter(x -> x.id().equals(vo.get("id").asString())).findFirst().orElseThrow();
        String target = vo.get("path").asString().replace("{table}", "readings");
        TestResponse a = send(rig, vo.get("method").asString(), target, o.body(), who);
        boolean allowed = c.get(vo.get("permission").asString()).asBoolean();
        assertThat(a.status() == 403).as("%s / %s: status %d %s, want allowed %s", name, o.id(), a.status(), a.body(), allowed).isEqualTo(!allowed);
        if (!allowed) {
          assertThat(errorCode(a)).as("%s / %s", name, o.id()).isEqualTo("FORBIDDEN");
        }
      }
      TestResponse nav = send(rig, "GET", "/api/admin/navigation", null, who);
      assertThat(nav.status()).as(name).isEqualTo(200);
      assertThat(sectionIds(nav).contains("partitions")).as("%s: navigation lists the section", name).isEqualTo(c.get("navigation").asBoolean());
    }
  }

  @Test
  void theRefusalsVector() throws IOException {
    JsonNode refusals = JsonMapper.builder().build().readTree(Conformance.read("admin/partitions.json")).get("refusals");
    assertThat(refusals.size()).isPositive();
    Rig rig = start(false);
    for (JsonNode c : refusals) {
      String body = c.has("body") ? c.get("body").toString() : null;
      TestResponse a = send(rig, c.get("method").asString(), c.get("path").asString(), body, OPERATOR_WITH_BOTH);
      JsonNode expect = c.get("expect");
      assertThat(a.status()).as("%s: %s", c.get("name").asString(), a.body()).isEqualTo(expect.get("status").asInt());
      if (expect.has("error")) {
        assertThat(errorCode(a)).as("%s: %s", c.get("name").asString(), a.body()).isEqualTo(expect.get("error").asString());
      }
    }
  }
}
