package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.admin.AdminConfigModule;
import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.StartupException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Naming or declining the suggested admin role (admin contract sections 3 and 11): the role name and
 * display name, an application's own role of the chosen name, the problems, and the warning of a
 * declined role that leaves nobody able to open the admin area.
 */
class AdminRoleOptionsTest {

  static final ApplicationCode APP = new ApplicationCode("orders");
  static final List<String> ADMIN_PERMISSIONS = AdminDefinitionsTest.ADMIN_PERMISSIONS;
  static final String NO_CONSOLE_VIEW_WARNING = "no declared role grants 'orders:admin:console:view'";

  @TempDir Path dir;
  FakePlatform platform;

  @BeforeEach
  void startPlatform() {
    platform = FakePlatform.start();
  }

  @AfterEach
  void stopPlatform() {
    platform.close();
  }

  App start(FlowCatalystModule fc, Module... extra) {
    AppBuilder b =
        App.builder()
            .configDirectory(dir)
            .environment(Contributor.env(platform))
            .module(PlatformAdminTest.http())
            .module(AdminModule.of(APP))
            .module(AdminConfigModule.of(APP))
            .module(PlatformAdminModule.of(APP));
    for (Module m : extra) {
      b.module(m);
    }
    return b.module(fc).start();
  }

  static Module define(Consumer<Definitions> contribute) {
    return Contributor.of("own", contribute);
  }

  static List<String> roleNames(DefinitionCatalog catalog) {
    return catalog.roles().stream().map(Definition.Role::name).toList();
  }

  static List<String> permissionCodes(DefinitionCatalog catalog) {
    return catalog.permissions().stream().map(Definition.Permission::code).toList();
  }

  // ---- naming the role ---------------------------------------------------------------------------

  @Test
  void theSuggestedRoleCanBeNamed() {
    try (App app = start(new FlowCatalystModule().adminRoleName("platform-operator"))) {
      DefinitionCatalog catalog = app.get(DefinitionCatalog.class);

      assertThat(catalog.roles()).hasSize(1);
      Definition.Role role = catalog.roles().get(0);
      assertThat(role.name()).isEqualTo("platform-operator");
      assertThat(role.displayName()).isEqualTo("Platform operator");
      assertThat(role.permissions()).containsExactlyElementsOf(ADMIN_PERMISSIONS);
      assertThat(role.description()).isNotBlank();
      assertThat(role.clientManaged()).isFalse();
      assertThat(roleNames(catalog)).doesNotContain("admin");
      assertThat(permissionCodes(catalog)).containsExactlyElementsOf(ADMIN_PERMISSIONS);
    }
  }

  @Test
  void theDisplayNameIsDerivedOrSet() {
    record Case(String name, FlowCatalystModule module, String want) {}
    List<Case> cases =
        List.of(
            new Case("on the default name", new FlowCatalystModule().adminRoleDisplayName("Console owner"), "Console owner"),
            new Case("on a chosen name", new FlowCatalystModule().adminRoleName("platform-operator").adminRoleDisplayName("Operator"), "Operator"),
            new Case("the default name is unchanged", new FlowCatalystModule(), "Administrator"),
            new Case("the derived name of a plain word", new FlowCatalystModule().adminRoleName("support"), "Support"),
            new Case("a chosen name admin keeps Administrator", new FlowCatalystModule().adminRoleName("admin"), "Administrator"));
    for (Case c : cases) {
      try (App app = start(c.module())) {
        List<Definition.Role> roles = app.get(DefinitionCatalog.class).roles();
        assertThat(roles).as(c.name()).hasSize(1);
        assertThat(roles.get(0).displayName()).as(c.name()).isEqualTo(c.want());
      }
    }
  }

  @Test
  void anApplicationRoleOfTheChosenNameIsKeptAndNothingIsAdded() {
    try (App app =
        start(
            new FlowCatalystModule().adminRoleName("platform-operator"),
            define(d -> d.permission("orders:admin:console:view", "Open the admin").role("platform-operator", "orders:admin:console:view")))) {
      DefinitionCatalog catalog = app.get(DefinitionCatalog.class);

      assertThat(catalog.roles()).hasSize(1);
      assertThat(catalog.roles().get(0).permissions()).containsExactly("orders:admin:console:view");
      assertThat(catalog.permissions()).hasSize(1);
    }
    // a role named admin, which is not the chosen name, does not stop the suggested role
    try (App app =
        start(
            new FlowCatalystModule().adminRoleName("platform-operator"),
            define(d -> d.permission("orders:admin:console:view", "Open the admin").role("admin", "orders:admin:console:view")))) {
      assertThat(roleNames(app.get(DefinitionCatalog.class))).containsExactly("admin", "platform-operator");
    }
  }

  @Test
  void anEmptyOrMalformedRoleNameIsAStartUpProblemAndNeverFallsBackToAdmin() {
    for (String name : new String[] {"", "orders:admin", "platform operator", "Operator"}) {
      assertThatThrownBy(() -> start(new FlowCatalystModule().adminRoleName(name)))
          .as("'%s'", name)
          .isInstanceOf(StartupException.class)
          .hasMessageContaining("role name must");
    }
  }

  // ---- declining the role ------------------------------------------------------------------------

  @Test
  void withoutAdminRoleAddsThePermissionsAndNoRole() {
    try (App app = start(new FlowCatalystModule().withoutAdminRole())) {
      DefinitionCatalog catalog = app.get(DefinitionCatalog.class);

      assertThat(catalog.roles()).isEmpty();
      assertThat(permissionCodes(catalog)).containsExactlyElementsOf(ADMIN_PERMISSIONS);
      assertThat(catalog.permissions()).allSatisfy(p -> assertThat(p.description()).isNotBlank());
    }
    // a permission the application declared itself is not declared twice, and its own admin role is kept
    try (App app =
        start(
            new FlowCatalystModule().withoutAdminRole(),
            define(d -> d.permission("orders:admin:console:view", "Open the admin").role("admin", "orders:admin:console:view")))) {
      assertThat(permissionCodes(app.get(DefinitionCatalog.class))).containsExactlyInAnyOrderElementsOf(ADMIN_PERMISSIONS);
      assertThat(roleNames(app.get(DefinitionCatalog.class))).containsExactly("admin");
    }
  }

  @Test
  void decliningTheRoleAndNamingItIsAStartUpProblem() {
    for (FlowCatalystModule fc :
        List.of(
            new FlowCatalystModule().withoutAdminRole().adminRoleName("platform-operator"),
            new FlowCatalystModule().adminRoleDisplayName("Operator").withoutAdminRole())) {
      assertThatThrownBy(() -> start(fc)).isInstanceOf(StartupException.class).hasMessageContaining("have no effect");
    }
  }

  // ---- the warning -------------------------------------------------------------------------------

  static PermissionMapper mapping(Map<String, Set<String>> table) {
    return PermissionMapper.byRole(table);
  }

  @Test
  void withoutAdminRoleWarnsWhenNoDeclaredRoleGrantsTheConsole() {
    record Case(String name, Consumer<Definitions> define, PermissionMapper mapping, boolean warn) {}
    Consumer<Definitions> clerk = d -> d.permission("orders:sales:order:read", "Read orders").role("clerk", "orders:sales:order:read");
    Consumer<Definitions> operator = d -> d.permission("orders:sales:order:read", "Read orders").role("operator", "orders:sales:order:read");
    List<Case> cases =
        List.of(
            new Case("no role at all", d -> {}, null, true),
            new Case("a role without it", clerk, null, true),
            new Case("a mapping that gives it to nobody declared", clerk, mapping(Map.of("orders:other", Set.of("orders:admin:console:view"))), true),
            new Case(
                "a role that grants it by its definition",
                d -> d.permission("orders:admin:console:view", "Open").role("operator", "orders:admin:console:view"),
                null,
                false),
            new Case("a role that gets it from the mapping", operator, mapping(Map.of("orders:operator", Set.of("orders:admin:console:view"))), false),
            new Case("a role that gets it through a wildcard in the mapping", operator, mapping(Map.of("orders:operator", Set.of("orders:admin:*:*"))), false));
    for (Case c : cases) {
      FlowCatalystModule fc = c.mapping() == null ? new FlowCatalystModule().withoutAdminRole() : new FlowCatalystModule().withoutAdminRole().withPermissionMapping(c.mapping());
      try (LogCapture logs = LogCapture.start();
          App app = start(fc, define(c.define()))) {
        String text = logs.text();
        int count = text.split(java.util.regex.Pattern.quote(NO_CONSOLE_VIEW_WARNING), -1).length - 1;
        assertThat(count).as("%s: warning logged %d times", c.name(), count).isEqualTo(c.warn() ? 1 : 0);
        if (c.warn()) {
          assertThat(text.lines().filter(l -> l.contains(NO_CONSOLE_VIEW_WARNING)).findFirst().orElseThrow()).as(c.name()).contains("WARN");
          // a warning, not a failure: the application started
          assertThat(app.get(DefinitionCatalog.class).roles()).isNotNull();
        }
      }
    }
  }

  @Test
  void theSuggestedRoleOrNoOptionNeverWarns() {
    for (FlowCatalystModule fc : List.of(new FlowCatalystModule(), new FlowCatalystModule().adminRoleName("platform-operator"))) {
      try (LogCapture logs = LogCapture.start();
          App app = start(fc)) {
        assertThat(logs.text()).doesNotContain(NO_CONSOLE_VIEW_WARNING);
        assertThat(app.get(DefinitionCatalog.class).roles()).hasSize(1);
      }
    }
  }
}
