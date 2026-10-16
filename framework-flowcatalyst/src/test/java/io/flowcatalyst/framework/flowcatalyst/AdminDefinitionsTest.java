package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.admin.AdminConfigModule;
import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.flowcatalyst.CategoryResult.Outcome;
import io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Mode;
import io.flowcatalyst.framework.flowcatalyst.DefinitionSync.Category;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The admin sections' permissions and the suggested {@code admin} role reach the platform through
 * the definition sync (M4e): explicit permissions, the platform's role-name rules, a dry run that
 * calls nothing.
 */
class AdminDefinitionsTest {

  static final ApplicationCode APP = new ApplicationCode("orders");
  static final List<String> ADMIN_PERMISSIONS =
      List.of(
          "orders:admin:config:view",
          "orders:admin:console:view",
          "orders:admin:event-types:view",
          "orders:admin:roles:view");

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

  App start(Module... extra) {
    var b =
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
    return b.module(new FlowCatalystModule()).start();
  }

  @Test
  void theCatalogHoldsTheAdminPermissionsAndOneAdminRoleGrantingThemExplicitly() {
    try (App app = start()) {
      DefinitionCatalog catalog = app.get(DefinitionCatalog.class);

      assertThat(catalog.permissions()).extracting(Definition.Permission::code).containsExactlyElementsOf(ADMIN_PERMISSIONS);
      assertThat(catalog.roles()).hasSize(1);
      Definition.Role role = catalog.roles().get(0);
      assertThat(role.name()).isEqualTo("admin");
      assertThat(role.displayName()).isEqualTo("Administrator");
      // explicit: the definition sync does not accept a wildcard in a role's permissions
      assertThat(role.permissions()).containsExactlyElementsOf(ADMIN_PERMISSIONS).noneMatch(p -> p.contains("*"));
    }
  }

  @Test
  void theNamesObeyThePlatformsRules() {
    try (App app = start()) {
      DefinitionCatalog catalog = app.get(DefinitionCatalog.class);
      // letters, digits and _.- per segment, colon-separated, no whitespace; the role has no prefix (the platform adds it)
      assertThat(catalog.roles().get(0).name()).matches("[A-Za-z0-9_.-]+");
      assertThat(catalog.permissions()).allSatisfy(p -> assertThat(p.code()).matches("[a-z0-9_-]+(:[a-z0-9_-]+){3}"));
    }
  }

  @Test
  void aDryRunReportsTheAdminRoleAndCallsNothing() {
    try (App app = start()) {
      SyncReport report = app.get(DefinitionSync.class).run(Mode.DRY_RUN);

      assertThat(platform.syncRequests()).isEmpty();
      assertThat(report.of(Category.ROLES).outcome()).isEqualTo(Outcome.WOULD_SYNC);
      assertThat(report.of(Category.ROLES).codes()).containsExactly("admin");
    }
  }

  @Test
  void applyingSendsTheRoleWithAllAdminPermissions() {
    try (App app = start()) {
      app.get(DefinitionSync.class).run(Mode.APPLY);

      JsonNode role =
          platform.syncRequests().stream().filter(r -> r.isSyncOf("roles")).findFirst().orElseThrow().json().get("roles").get(0);
      assertThat(role.get("name").asString()).isEqualTo("admin");
      assertThat(role.get("permissions").valueStream().map(JsonNode::asString)).containsExactlyElementsOf(ADMIN_PERMISSIONS);
    }
  }

  @Test
  void anApplicationThatDeclaresItsOwnAdminRoleKeepsIt() {
    Contributor own =
        Contributor.of("own", d -> d.permission("orders:admin:console:view", "Open the admin").role("admin", "orders:admin:console:view"));
    try (App app = start(own)) {
      DefinitionCatalog catalog = app.get(DefinitionCatalog.class);

      assertThat(catalog.roles()).hasSize(1);
      assertThat(catalog.roles().get(0).permissions()).containsExactly("orders:admin:console:view");
      assertThat(catalog.permissions()).hasSize(1);
    }
  }

  @Test
  void withoutAdminSectionsNothingIsAdded() {
    var b = App.builder().configDirectory(dir).environment(Contributor.env(platform)).module(new FlowCatalystModule());
    try (App app = b.start()) {
      assertThat(app.get(DefinitionCatalog.class).roles()).isEmpty();
      assertThat(app.get(DefinitionCatalog.class).permissions()).isEmpty();
    }
  }
}
