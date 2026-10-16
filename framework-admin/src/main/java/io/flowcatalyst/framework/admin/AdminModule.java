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

  private final ApplicationCode application;
  private final AdminApi.Operations operations;
  private final AdminSection about;

  private AdminModule(ApplicationCode application) {
    this.application = application;
    String view = consoleView(application);
    this.operations = new AdminApi.Operations(view);
    this.about =
        AdminSection.of("about", "About", "/admin/about", view)
            .icon("i-lucide-info")
            .description("What this application is and whether it is healthy.")
            .serving(operations.navigation, operations.about);
  }

  /**
   * The admin module of an application.
   *
   * @param application the application code; every admin permission starts with it
   * @return the module
   */
  public static AdminModule of(ApplicationCode application) {
    return new AdminModule(application);
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
          List<Problem> problems = Admin.problems(sections, application.value());
          if (!problems.isEmpty()) {
            throw new StartupException(problems);
          }
          return new AdminRegistry(sections);
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
