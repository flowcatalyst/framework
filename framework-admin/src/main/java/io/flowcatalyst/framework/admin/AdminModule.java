package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.admin.AdminApi.AdminAbout;
import io.flowcatalyst.framework.admin.AdminApi.AdminCheck;
import io.flowcatalyst.framework.admin.AdminApi.AdminNavigation;
import io.flowcatalyst.framework.admin.AdminApi.AdminSectionView;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.HealthReport;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

/**
 * The application admin skeleton (M4d, admin contract). Registers its own {@code about} section
 * first (so the registry's rules cover the framework's operations too), serves {@code GET
 * /api/admin/navigation} and {@code GET /api/admin/about}, and at start-up checks the whole
 * registry. Other modules register their sections with {@link Admin#section}.
 */
public final class AdminModule implements Module {

  /** The module id. */
  public static final String ID = "admin";

  /** How the admin area is declared. */
  public enum Option {
    /**
     * The whole admin area is for platform operators only (admin contract section 3a, rule 6): the
     * framework's own {@code about} section, with the navigation and the about operation, is
     * operators-only (a caller whose tier is not {@code ANCHOR} gets {@code 403 FORBIDDEN} after the
     * permission check), so {@code {app}:admin:console:view} becomes an operators-only permission
     * that no client-managed role may carry; and every registered section that is not itself
     * operators-only is a start-up problem. The framework's config section and the platform
     * sections of the FlowCatalyst module are not operators-only, so an application that wants
     * them cannot declare an operators-only area.
     */
    OPERATORS_ONLY
  }

  private final ApplicationCode application;
  private final boolean operatorsOnly;
  private final AdminApi.Operations operations;
  private final AdminSection about;

  private AdminModule(ApplicationCode application, boolean operatorsOnly) {
    this.application = application;
    this.operatorsOnly = operatorsOnly;
    String view = consoleView(application);
    this.operations = new AdminApi.Operations(view, operatorsOnly);
    AdminSection section =
        AdminSection.of("about", "About", "/admin/about", view)
            .icon("i-lucide-info")
            .description("What this application is and whether it is healthy.");
    if (operatorsOnly) {
      section = section.operatorsOnly();
    }
    this.about = section.serving(operations.navigation, operations.about);
  }

  /**
   * The admin module of an application.
   *
   * @param application the application code; every admin permission starts with it
   * @param options how the area is declared, for example {@link Option#OPERATORS_ONLY}
   * @return the module
   */
  public static AdminModule of(ApplicationCode application, Option... options) {
    return new AdminModule(application, java.util.Arrays.asList(options).contains(Option.OPERATORS_ONLY));
  }

  /**
   * Whether the area was declared with {@link Option#OPERATORS_ONLY}, so a module that registers a
   * section can mark it accordingly.
   *
   * @return true for an operators-only area
   */
  public boolean operatorsOnly() {
    return operatorsOnly;
  }

  /**
   * The permission that opens the admin area at all: {@code {app}:admin:console:view}.
   *
   * @param application the application code
   * @return the permission
   */
  public static String consoleView(ApplicationCode application) {
    return application.value() + ":admin:console:view";
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, about);
    // Built when first asked for (the module's own boot asks): the whole registry is checked then,
    // after every module registered, so a problem fails start-up before anything listens.
    app.provide(
        AdminRegistry.class,
        lookup -> {
          List<AdminSection> sections = Admin.sections(lookup);
          List<Problem> problems = Admin.problems(sections, application.value(), operatorsOnly);
          if (!problems.isEmpty()) {
            throw new StartupException(problems);
          }
          return new AdminRegistry(sections, operatorsOnly);
        });
  }

  @Override
  public void boot(App app) {
    AdminRegistry registry = app.get(AdminRegistry.class);
    Instant startedAt = app.get(InstantSource.class).instant();
    String environment = app.config().environment();
    Routes routes = app.get(Routes.class).forModule(ID);

    routes.bind(
        operations.navigation,
        r -> {
          List<AdminSectionView> views = new ArrayList<>();
          for (AdminSection s : Admin.visibleTo(registry.sections(), r.context())) {
            views.add(new AdminSectionView(s.id(), s.title(), s.path(), s.permission(), s.icon(), s.description()));
          }
          return Outcome.ok(new AdminNavigation(views));
        });

    routes.bind(
        operations.about,
        r -> {
          HealthReport report = app.health().check();
          List<AdminCheck> checks = AdminApi.checks(report);
          return Outcome.ok(
              new AdminAbout(
                  application.value(),
                  AdminModule.class.getPackage().getImplementationVersion(),
                  environment,
                  startedAt,
                  report.isReady(),
                  checks));
        });
  }
}
