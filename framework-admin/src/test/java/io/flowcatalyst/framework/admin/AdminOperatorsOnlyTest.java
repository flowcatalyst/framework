package io.flowcatalyst.framework.admin;

import static io.flowcatalyst.framework.admin.AdminFixture.APP;
import static io.flowcatalyst.framework.admin.AdminFixture.THINGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.OpenApiDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Operators-only sections and areas (admin contract section 3a and section 2, rule 5): the mark, the
 * start-up problems, the navigation filter, the permissions a client-managed role must not carry,
 * the gate of the skeleton's own operations and the OpenAPI marker.
 */
class AdminOperatorsOnlyTest {

  record Out(String x) {}

  static Operation<NoBody, Out> op(String id, String pattern, String permission, boolean marked) {
    Operation.Builder<NoBody, Out> b = Operation.get(pattern, Out.class).id(id).tag("admin").permission(permission);
    return (marked ? b.operatorsOnly() : b).build();
  }

  static AdminSection vault() {
    return AdminSection.of("vault", "Vault", "/admin/vault", "acme:admin:vault:view")
        .operatorsOnly()
        .serving(
            op("getVault", "/api/admin/vault", "acme:admin:vault:view", true),
            op("putVault", "/api/admin/vault/x", "acme:admin:vault:manage", true));
  }

  static List<AdminSection> sections() {
    return List.of(AdminSection.of("things", "Things", "/admin/things", "acme:admin:things:view"), vault());
  }

  static Context caller(String tier, String... permissions) {
    Context c = Context.of(new ClientId("demo")).withPrincipal(new PrincipalId("usr_1")).withPermissions(Set.of(permissions));
    return tier == null ? c : c.withTier(tier);
  }

  // ---- the mark ----------------------------------------------------------------------------------

  @Test
  void aSectionIsOperatorsOnlyOnlyWhenMarked() {
    assertThat(THINGS.forOperators()).isFalse();
    assertThat(vault().forOperators()).isTrue();
    // the mark survives the other copies
    assertThat(vault().icon("i-lucide-lock").description("d").forOperators()).isTrue();
    assertThat(AdminSection.of("v", "V", "/admin/v", "acme:admin:v:view").operatorsOnly().icon("x").forOperators()).isTrue();
  }

  @Test
  void anOperationOfAnOperatorsOnlySectionThatIsNotDeclaredOperatorsOnlyIsAStartUpProblem() {
    Operation<NoBody, Out> unmarked = op("getVault", "/api/admin/vault", "acme:admin:vault:view", false);

    assertThatThrownBy(() -> AdminSection.of("vault", "Vault", "/admin/vault", "acme:admin:vault:view").operatorsOnly().serving(unmarked))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("operation getVault is in an operators-only section but is not declared with operatorsOnly()");
    // whichever order the section is built in
    assertThatThrownBy(() -> AdminSection.of("vault", "Vault", "/admin/vault", "acme:admin:vault:view").serving(unmarked).operatorsOnly())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is not declared with operatorsOnly()");
    // an operators-only operation in an ordinary section is allowed
    AdminSection.of("v", "V", "/admin/v", "acme:admin:vault:view").serving(op("a", "/api/admin/a", "acme:admin:vault:view", true));
  }

  // ---- the navigation ----------------------------------------------------------------------------

  @Test
  void theNavigationHidesAnOperatorsOnlySectionFromEveryoneButAnOperator() {
    String[][] cases = {
      {"ANCHOR", "things,vault"}, {"PARTNER", "things"}, {"CLIENT", "things"}, {null, "things"}, {"anchor", "things"},
    };
    for (String[] c : cases) {
      List<String> ids = new ArrayList<>();
      for (AdminSection s : Admin.visibleTo(sections(), caller(c[0], "acme:admin:*:*"))) {
        ids.add(s.id());
      }
      assertThat(String.join(",", ids)).as("tier %s", c[0]).isEqualTo(c[1]);
    }
    // without the permission an operator does not see it either
    assertThat(Admin.visibleTo(sections(), caller("ANCHOR", "acme:admin:things:view"))).extracting(AdminSection::id).containsExactly("things");
  }

  // ---- the permissions a client-managed role must not carry --------------------------------------

  @Test
  void theOperatorsOnlyPermissionsAreTheSectionsAndItsOperationsWithoutTheOthers() {
    assertThat(Admin.operatorsOnlyPermissions(sections())).containsExactly("acme:admin:vault:manage", "acme:admin:vault:view");
    assertThat(Admin.operatorsOnlyPermissions(List.of(THINGS))).isEmpty();
  }

  @Test
  void conflictsFollowThePlatformsWildcardRule() {
    Object[][] cases = {
      {List.of(), List.of()},
      {List.of("acme:admin:things:view"), List.of()},
      {List.of("acme:admin:vault:view"), List.of("acme:admin:vault:view")},
      {List.of("acme:admin:vault:manage"), List.of("acme:admin:vault:manage")},
      {List.of("acme:admin:*:*"), List.of("acme:admin:vault:manage", "acme:admin:vault:view")},
      {List.of("acme:admin:vault:*"), List.of("acme:admin:vault:manage", "acme:admin:vault:view")},
      {List.of("acme:admin:things:*"), List.of()},
      {List.of("acme:admin:*"), List.of()},
      {List.of("acme:admin:*:*:*"), List.of()},
      {List.of("billing:admin:*:*"), List.of()},
      {List.of("", "acme::vault:view"), List.of()},
    };
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked")
      List<String> held = (List<String>) c[0];
      @SuppressWarnings("unchecked")
      List<String> expected = (List<String>) c[1];
      assertThat(Admin.operatorsOnlyConflicts(sections(), held)).as("%s", held).containsExactlyElementsOf(expected);
    }
    assertThat(Admin.operatorsOnlyConflicts(List.of(THINGS), List.of("acme:admin:*:*"))).isEmpty();
  }

  // ---- the area ----------------------------------------------------------------------------------

  static TestResponse get(App app, String target, String perms, String tier) {
    var call = AdminFixture.client(app).get(target);
    if (perms != null) {
      call = call.header("X-Perms", perms);
    }
    if (tier != null) {
      call = call.header("X-Tier", tier);
    }
    return call.send();
  }

  @Test
  void theSkeletonsOperationsOfAnOperatorsOnlyAreaNeedAnAnchorAfterThePermission() {
    try (App app = AdminFixture.areaBuilder().start()) {
      assertThat(app.get(AdminRegistry.class).operatorsOnlyArea()).isTrue();
      String view = "acme:admin:console:view";
      String wild = "acme:admin:*:*";
      for (String target : new String[] {"/api/admin/navigation", "/api/admin/about"}) {
        assertThat(get(app, target, view, "ANCHOR").status()).as(target).isEqualTo(200);
        assertThat(get(app, target, wild, "ANCHOR").status()).as(target).isEqualTo(200);
        for (String tier : new String[] {"CLIENT", "PARTNER", null, "anchor"}) {
          for (String perms : new String[] {view, wild}) {
            TestResponse r = get(app, target, perms, tier);
            assertThat(r.status()).as("%s %s %s", target, tier, perms).isEqualTo(403);
            assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
            assertThat(r.tree().get("message").asString()).isEqualTo("this is for platform operators only");
          }
        }
        TestResponse noPermission = get(app, target, "acme:admin:other:view", "ANCHOR");
        assertThat(noPermission.status()).isEqualTo(403);
        assertThat(noPermission.tree().get("message").asString()).isEqualTo("this needs the permission " + view);
        // the permission is checked first: a client without it is told about the permission
        assertThat(get(app, target, "acme:admin:other:view", "CLIENT").tree().get("message").asString())
            .isEqualTo("this needs the permission " + view);
        assertThat(get(app, target, null, "ANCHOR").status()).isEqualTo(401);
      }
      assertThat(AdminSectionIds.of(get(app, "/api/admin/navigation", view, "ANCHOR"))).containsExactly("about");
    }
  }

  @Test
  void withoutTheOptionTheSkeletonIsOpenToAnyTier() {
    try (App app = AdminFixture.start()) {
      assertThat(app.get(AdminRegistry.class).operatorsOnlyArea()).isFalse();
      assertThat(get(app, "/api/admin/about", "acme:admin:console:view", "CLIENT").status()).isEqualTo(200);
      assertThat(get(app, "/api/admin/navigation", "acme:admin:console:view", null).status()).isEqualTo(200);
    }
  }

  static AdminSection reports(String id, boolean marked) {
    AdminSection s = AdminSection.of(id, id.substring(0, 1).toUpperCase() + id.substring(1), "/admin/" + id, "acme:admin:" + id + ":view");
    return marked ? s.operatorsOnly() : s;
  }

  @Test
  void anOperatorsOnlyAreaRefusesASectionThatIsNot() {
    assertThatThrownBy(() -> AdminFixture.areaBuilder(AdminFixture.sections(reports("reports", true), reports("users", false))).start())
        .isInstanceOfSatisfying(
            StartupException.class,
            e -> {
              assertThat(e.getMessage())
                  .contains("admin section 'users' is not operators-only, but the admin area is declared operators-only"
                      + " (AdminModule.Option.OPERATORS_ONLY): every section of it must be")
                  .doesNotContain("section 'reports'")
                  .doesNotContain("section 'about'");
              assertThat(e.problems()).extracting(Problem::module).containsOnly("admin");
            });
    // an area of operators-only sections builds, and an operator sees them
    try (App app = AdminFixture.areaBuilder(AdminFixture.sections(reports("reports", true))).start()) {
      assertThat(AdminSectionIds.of(get(app, "/api/admin/navigation", "acme:admin:*:*", "ANCHOR"))).containsExactly("about", "reports");
      assertThat(AdminSectionIds.of(get(app, "/api/admin/navigation", "acme:admin:*:*", "ANCHOR"))).doesNotContain("users");
    }
    // without the option the same sections build
    try (App app = AdminFixture.builder(AdminFixture.sections(reports("reports", true), reports("users", false))).start()) {
      assertThat(AdminSectionIds.of(get(app, "/api/admin/navigation", "acme:admin:*:*", "CLIENT"))).containsExactly("about", "users");
    }
  }

  @Test
  void theConfigSectionCannotBePartOfAnOperatorsOnlyArea() {
    assertThatThrownBy(() -> AdminFixture.areaBuilder(AdminConfigModule.of(APP)).start())
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("admin section 'config' is not operators-only");
  }

  @Test
  void theOpenApiDocumentMarksTheSkeletonOnlyInAnOperatorsOnlyArea() {
    for (boolean only : new boolean[] {true, false}) {
      var declarations = (only ? AdminFixture.areaBuilder() : AdminFixture.builder()).declarations();
      String json =
          switch (OpenApi.document(declarations)) {
            case OpenApiDocument.Written w -> w.json();
            case OpenApiDocument.Refused r -> throw new AssertionError(r.problems());
          };
      int marked = json.split("\"x-fw-operators-only\" ?: ?true", -1).length - 1;
      assertThat(marked).as("operators-only %s", only).isEqualTo(only ? 2 : 0);
      JsonNode paths = JsonMapper.builder().build().readTree(json).get("paths");
      assertThat(paths.get("/api/admin/navigation").get("get").has("x-fw-operators-only")).isEqualTo(only);
      assertThat(paths.get("/api/admin/about").get("get").has("x-fw-operators-only")).isEqualTo(only);
    }
  }

  @Test
  void theAreaIsReadableByASectionModule() {
    assertThat(AdminModule.of(APP, AdminModule.Option.OPERATORS_ONLY).operatorsOnly()).isTrue();
    assertThat(AdminModule.of(APP).operatorsOnly()).isFalse();
  }

  /** The section ids of a navigation answer. */
  static final class AdminSectionIds {
    private AdminSectionIds() {}

    static List<String> of(TestResponse r) {
      List<String> ids = new ArrayList<>();
      for (JsonNode s : r.tree().get("sections")) {
        ids.add(s.get("id").asString());
      }
      return ids;
    }
  }
}
