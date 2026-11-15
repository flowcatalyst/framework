package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminConfigModule;
import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Mode;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminModule;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A role a client's own administrators may assign (client-managed) must not carry the permission of
 * an operators-only admin section (admin contract section 3a, rules 4 and 6): not in its
 * definition, and not through the application's permission mapping, wildcards included. Start-up
 * refuses it, and so does the sync, before it sends anything.
 */
class OperatorsOnlyGuardTest {

  static final ApplicationCode APP = new ApplicationCode("orders");
  static final String PARTITIONS_VIEW = "orders:admin:partitions:view";

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

  record Done(boolean done) {}

  static final Operation<NoBody, Done> RUN =
      Operation.post("/api/admin/partitions/run", Done.class)
          .id("runPartitions")
          .tag("admin")
          .permission("orders:admin:partitions:manage")
          .operatorsOnly()
          .build();

  /** A module registering an operators-only section whose permissions are view (the section's) and manage (an operation's). */
  static Module operatorsSection() {
    return new Module() {
      @Override
      public String id() {
        return "partitions-stand-in";
      }

      @Override
      public void register(AppBuilder app) {
        Admin.section(
            app, AdminSection.of("partitions", "Partitions", "/admin/partitions", PARTITIONS_VIEW).operatorsOnly().serving(RUN));
      }

      @Override
      public void boot(App app) {
        app.get(Routes.class).forModule(id()).bind(RUN, r -> Outcome.ok(new Done(true)));
      }
    };
  }

  /**
   * The real partitions section (its five operations and permissions, as an application lists
   * {@code PartitionsAdminModule}), registered without the partitions module behind it: the guard
   * reads the registry, not the handlers.
   */
  static Module realPartitionsSection() {
    return new Module() {
      @Override
      public String id() {
        return "partitions-section";
      }

      @Override
      public void register(AppBuilder app) {
        Admin.section(app, PartitionsAdminModule.section(APP));
      }
    };
  }

  static Module define(Consumer<Definitions> contribute) {
    return Contributor.of("own", contribute);
  }

  App start(FlowCatalystModule fc, Module... extra) {
    return startWith(Map.of(), fc, extra);
  }

  App startWith(Map<String, String> more, FlowCatalystModule fc, Module... extra) {
    Map<String, String> env = new HashMap<>(Contributor.env(platform));
    env.putAll(more);
    AppBuilder b =
        App.builder()
            .configDirectory(dir)
            .environment(env)
            .module(PlatformAdminTest.http())
            .module(AdminModule.of(APP))
            .module(AdminConfigModule.of(APP));
    for (Module m : extra) {
      b.module(m);
    }
    return b.module(fc).start();
  }

  static PermissionMapper mapping(String role, String... permissions) {
    return PermissionMapper.byRole(Map.of(role, Set.of(permissions)));
  }

  // ---- the refusals ------------------------------------------------------------------------------

  @Test
  void aClientManagedRoleWithAnOperatorsOnlyPermissionByItsDefinitionFailsStartUp() {
    assertThatThrownBy(
            () ->
                start(
                    new FlowCatalystModule(),
                    operatorsSection(),
                    define(
                        d ->
                            d.permission(PARTITIONS_VIEW, "View partitions")
                                .role(Definition.Role.of("operator", PARTITIONS_VIEW).withClientManaged()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("role 'orders:operator'")
        .hasMessageContaining("client-managed")
        .hasMessageContaining("the role's definition")
        .hasMessageContaining("'" + PARTITIONS_VIEW + "'");
  }

  @Test
  void theManagePermissionAmongOthersIsRefusedToo() {
    assertThatThrownBy(
            () ->
                start(
                    new FlowCatalystModule(),
                    operatorsSection(),
                    define(
                        d ->
                            d.permission("orders:sales:order:read", "Read orders")
                                .permission("orders:admin:partitions:manage", "Manage partitions")
                                .role(
                                    Definition.Role.of("clerk", "orders:sales:order:read", "orders:admin:partitions:manage")
                                        .withClientManaged()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("role 'orders:clerk'")
        .hasMessageContaining("'orders:admin:partitions:manage'");
  }

  @Test
  void theExactPermissionThroughThePermissionMappingIsRefused() {
    FlowCatalystModule fc =
        new FlowCatalystModule().withPermissionMapping(mapping("orders:operator", "orders:sales:order:read", PARTITIONS_VIEW));

    assertThatThrownBy(
            () ->
                start(
                    fc,
                    operatorsSection(),
                    define(d -> d.role(Definition.Role.of("operator", "orders:sales:order:read").withClientManaged()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("role 'orders:operator'")
        .hasMessageContaining("the application's permission mapping")
        .hasMessageContaining("'" + PARTITIONS_VIEW + "'");
  }

  @Test
  void aWildcardThroughThePermissionMappingIsRefusedForEveryPermissionItGrants() {
    FlowCatalystModule fc = new FlowCatalystModule().withPermissionMapping(mapping("orders:operator", "orders:admin:*:*"));

    assertThatThrownBy(
            () ->
                start(
                    fc,
                    operatorsSection(),
                    define(d -> d.role(Definition.Role.of("operator", "orders:sales:order:read").withClientManaged()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("role 'orders:operator'")
        .hasMessageContaining("'" + PARTITIONS_VIEW + "'")
        .hasMessageContaining("(through 'orders:admin:*:*')");
  }

  @Test
  void aWildcardOfOneSegmentIsRefused() {
    FlowCatalystModule fc = new FlowCatalystModule().withPermissionMapping(mapping("orders:operator", "orders:admin:partitions:*"));

    assertThatThrownBy(
            () ->
                start(
                    fc,
                    operatorsSection(),
                    define(d -> d.role(Definition.Role.of("operator", "orders:sales:order:read").withClientManaged()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("'" + PARTITIONS_VIEW + "'")
        .hasMessageContaining("(through 'orders:admin:partitions:*')");
  }

  @Test
  void aDisabledModuleRefusesToo() {
    assertThatThrownBy(
            () ->
                startWith(
                    Map.of("FLOWCATALYST_ENABLED", "false"),
                    new FlowCatalystModule(),
                    operatorsSection(),
                    define(d -> d.role(Definition.Role.of("operator", PARTITIONS_VIEW).withClientManaged()))))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("'" + PARTITIONS_VIEW + "'");
  }

  // ---- the real partitions section ---------------------------------------------------------------

  @Test
  void theRealPartitionsSectionRefusesAClientManagedRoleThatHoldsItsViewOrManagePermission() {
    for (String permission : new String[] {"orders:admin:partitions:view", "orders:admin:partitions:manage"}) {
      assertThatThrownBy(
              () ->
                  start(
                      new FlowCatalystModule(),
                      realPartitionsSection(),
                      define(d -> d.permission(permission, "Partitions").role(Definition.Role.of("operator", permission).withClientManaged()))))
          .as(permission)
          .isInstanceOf(StartupException.class)
          .hasMessageContaining("role 'orders:operator'")
          .hasMessageContaining("client-managed")
          .hasMessageContaining("'" + permission + "'");
    }
  }

  @Test
  void theRealPartitionsSectionRefusesTheAdminWildcardAndTheSectionWildcardThroughTheMapping() {
    for (String wildcard : new String[] {"orders:admin:*:*", "orders:admin:partitions:*"}) {
      FlowCatalystModule fc = new FlowCatalystModule().withPermissionMapping(mapping("orders:operator", wildcard));
      assertThatThrownBy(
              () ->
                  start(
                      fc,
                      realPartitionsSection(),
                      define(d -> d.role(Definition.Role.of("operator", "orders:sales:order:read").withClientManaged()))))
          .as(wildcard)
          .isInstanceOf(StartupException.class)
          .hasMessageContaining("role 'orders:operator'")
          .hasMessageContaining("'orders:admin:partitions:view'")
          .hasMessageContaining("'orders:admin:partitions:manage'")
          .hasMessageContaining("(through '" + wildcard + "')");
    }
  }

  @Test
  void theRealPartitionsSectionIsHeldByARoleThatIsNotClientManagedAndBySuggestedAdminRole() {
    FlowCatalystModule fc = new FlowCatalystModule().withPermissionMapping(mapping("orders:admin", "orders:admin:*:*"));
    try (App app =
        start(
            fc,
            realPartitionsSection(),
            define(
                d ->
                    d.permission("orders:admin:partitions:view", "View partitions")
                        .permission("orders:admin:partitions:manage", "Manage partitions")
                        .role(Definition.Role.of("dba", "orders:admin:partitions:view", "orders:admin:partitions:manage"))))) {
      assertThat(app.get(DefinitionCatalog.class).roles()).extracting(Definition.Role::name).contains("dba", "admin");
    }
  }

  // ---- what may carry them -----------------------------------------------------------------------

  @Test
  void aRoleThatIsNotClientManagedMayHoldThePermissionsAndTheMappingsWildcard() {
    FlowCatalystModule fc =
        new FlowCatalystModule()
            .withPermissionMapping(
                PermissionMapper.byRole(
                    Map.of(
                        "orders:admin", Set.of("orders:admin:*:*"),
                        "orders:operator", Set.of("orders:sales:order:read", "orders:admin:config:view", "orders:admin:console:view"))));
    try (App app =
        start(
            fc,
            operatorsSection(),
            define(
                d ->
                    d.permission(PARTITIONS_VIEW, "View partitions")
                        .role(Definition.Role.of("owner", PARTITIONS_VIEW, "orders:admin:partitions:manage"))))) {
      assertThat(app.get(DefinitionCatalog.class).roles()).extracting(Definition.Role::name).contains("owner", "admin");
    }
  }

  @Test
  void aClientManagedRoleWithOtherPermissionsIncludingAnotherAdminSectionsIsAccepted() {
    FlowCatalystModule fc =
        new FlowCatalystModule()
            .withPermissionMapping(
                PermissionMapper.byRole(
                    Map.of(
                        "orders:admin", Set.of("orders:admin:*:*"),
                        "orders:operator", Set.of("orders:sales:order:read", "orders:admin:config:view", "orders:admin:console:view"))));
    try (App app =
        start(
            fc,
            operatorsSection(),
            define(
                d ->
                    d.permission("orders:sales:order:read", "Read orders")
                        .permission("orders:admin:config:view", "View config")
                        .role(Definition.Role.of("operator", "orders:sales:order:read", "orders:admin:config:view").withClientManaged())))) {
      assertThat(app.get(DefinitionCatalog.class).roles()).extracting(Definition.Role::name).contains("operator");
    }
  }

  @Test
  void theMappingsWildcardBelongsToTheRoleItNamesNotToAClientManagedOne() {
    // the wildcard is the mapping's for orders:admin, not for the client-managed orders:operator
    FlowCatalystModule fc = new FlowCatalystModule().withPermissionMapping(mapping("orders:admin", "orders:admin:*:*"));
    try (App app =
        start(
            fc,
            operatorsSection(),
            define(d -> d.role(Definition.Role.of("operator", "orders:sales:order:read").withClientManaged())))) {
      assertThat(app.get(DefinitionCatalog.class).roles()).extracting(Definition.Role::name).contains("operator");
    }
  }

  @Test
  void withoutAnOperatorsOnlySectionTheGuardChecksNothing() {
    FlowCatalystModule fc = new FlowCatalystModule().withPermissionMapping(mapping("orders:operator", "orders:admin:*:*"));
    try (App app =
        start(
            fc,
            define(
                d ->
                    d.permission(PARTITIONS_VIEW, "View partitions")
                        .role(Definition.Role.of("operator", PARTITIONS_VIEW).withClientManaged())))) {
      assertThat(app.get(DefinitionCatalog.class).roles()).extracting(Definition.Role::name).contains("operator");
    }
  }

  // ---- the operators-only area -------------------------------------------------------------------

  /** The area: only the framework's own about section, so console:view is an operators-only permission. */
  App startArea(FlowCatalystModule fc, Module... extra) {
    AppBuilder b =
        App.builder()
            .configDirectory(dir)
            .environment(Contributor.env(platform))
            .module(PlatformAdminTest.http())
            .module(AdminModule.of(APP, AdminModule.Option.OPERATORS_ONLY));
    for (Module m : extra) {
      b.module(m);
    }
    return b.module(fc).start();
  }

  @Test
  void inAnOperatorsOnlyAreaAClientManagedRoleMayNotOpenTheConsole() {
    record Case(String name, Consumer<Definitions> define, PermissionMapper mapping, List<String> want) {}
    List<Case> cases =
        List.of(
            new Case(
                "in the role's definition",
                d ->
                    d.permission("orders:admin:console:view", "Open the admin area")
                        .role(Definition.Role.of("clerk", "orders:admin:console:view").withClientManaged()),
                null,
                List.of("role 'orders:clerk'", "client-managed", "the role's definition", "'orders:admin:console:view'")),
            new Case(
                "through the permission mapping",
                d -> d.role(Definition.Role.of("clerk", "orders:sales:order:read").withClientManaged()),
                mapping("orders:clerk", "orders:sales:order:read", "orders:admin:console:view"),
                List.of("role 'orders:clerk'", "the application's permission mapping", "'orders:admin:console:view'")),
            new Case(
                "through the wildcard in the mapping",
                d -> d.role(Definition.Role.of("clerk", "orders:sales:order:read").withClientManaged()),
                mapping("orders:clerk", "orders:admin:*:*"),
                List.of("role 'orders:clerk'", "'orders:admin:console:view'", "(through 'orders:admin:*:*')")));
    for (Case c : cases) {
      FlowCatalystModule fc = c.mapping() == null ? new FlowCatalystModule() : new FlowCatalystModule().withPermissionMapping(c.mapping());
      assertThatThrownBy(() -> startArea(fc, define(c.define())))
          .as(c.name())
          .isInstanceOf(StartupException.class)
          .satisfies(e -> c.want().forEach(w -> assertThat(e.getMessage()).as(c.name()).contains(w)));
      // the same role is accepted when the area is not operators-only (today's behaviour)
      try (App app = start(fc, define(c.define()))) {
        assertThat(app.get(DefinitionCatalog.class).roles()).as(c.name()).extracting(Definition.Role::name).contains("clerk");
      }
    }
  }

  @Test
  void theSuggestedRoleMayHoldOperatorsOnlyPermissionsUnderAnyName() {
    for (String name : new String[] {"admin", "platform-operator"}) {
      FlowCatalystModule fc = new FlowCatalystModule().adminRoleName(name).withPermissionMapping(mapping("orders:" + name, "orders:admin:*:*"));
      try (App app = startArea(fc)) {
        List<Definition.Role> roles = app.get(DefinitionCatalog.class).roles();
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).name()).isEqualTo(name);
        assertThat(roles.get(0).clientManaged()).isFalse();
        assertThat(roles.get(0).permissions()).contains("orders:admin:console:view");
      }
    }
  }

  // ---- the sync ----------------------------------------------------------------------------------

  @Test
  void theSyncRefusesAClientManagedRoleWithAnOperatorsOnlyPermissionAndSendsNothing() {
    List<AdminSection> sections = List.of(AdminSection.of("partitions", "Partitions", "/admin/partitions", PARTITIONS_VIEW).operatorsOnly());
    PermissionMapper wildcard = mapping("orders:clerk", "orders:admin:*:*");
    record Case(String name, Definition.Role role, String want) {}
    List<Case> cases =
        List.of(
            new Case("by definition", Definition.Role.of("operator", PARTITIONS_VIEW).withClientManaged(), "role 'orders:operator'"),
            new Case("by the mapping's wildcard", Definition.Role.of("clerk", "orders:sales:order:read").withClientManaged(), "(through 'orders:admin:*:*')"),
            new Case("not client-managed", Definition.Role.of("clerk", PARTITIONS_VIEW), ""),
            new Case("another permission", Definition.Role.of("operator", "orders:sales:order:read").withClientManaged(), ""));
    for (Case c : cases) {
      DefinitionCatalog catalog =
          DefinitionCatalog.build(
              List.of(new Definitions.Contribution("own", new Definition.Permission(PARTITIONS_VIEW, "x")), new Definitions.Contribution("own", c.role())),
              APP,
              false);
      // a disabled client: past the guard, that is what stops the sync
      DefinitionSync sync = new DefinitionSync(PlatformClient.disabled(), catalog, () -> new JobTargets(null, false, false, "/fc"), new OperatorsOnlyGuard(sections, wildcard, "orders"));
      if (c.want().isEmpty()) {
        assertThatThrownBy(() -> sync.run(Mode.APPLY)).as(c.name()).hasMessageContaining("disabled");
      } else {
        assertThatThrownBy(() -> sync.run(Mode.APPLY))
            .as(c.name())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("the definitions are not sent")
            .hasMessageContaining(c.want())
            .hasMessageContaining("operators-only");
      }
    }
    assertThat(platform.syncRequests()).isEmpty();
  }
}
