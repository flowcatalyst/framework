package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.admin.AdminConfigApi.AdminConfig;
import io.flowcatalyst.framework.admin.AdminConfigApi.AdminConfigSetting;
import io.flowcatalyst.framework.admin.AdminConfigApi.AdminHealth;
import io.flowcatalyst.framework.admin.AdminConfigApi.ConfigState;
import io.flowcatalyst.framework.config.SettingStatus;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.HealthReport;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The admin config section (M4e slice 3): which settings the application declares, whether each
 * has a value and where it came from (a file, an environment variable or the default), the
 * application's health, and the status of its secrets. Secrets are reported as set or unset and
 * never shown; neither is a value whose key names a credential, nor the user info and query of a
 * URL. List it after {@link AdminModule}; the section needs {@code {app}:admin:config:view}.
 *
 * <p>What it does not do: it does not re-run the modules' range checks (a value out of range fails
 * start-up, so every setting shown has passed them), and it does not look at per-client secrets
 * ({@code SecretResolver}), which are looked up by client and name.
 */
public final class AdminConfigModule implements Module {

  /** The module id. */
  public static final String ID = "admin-config";

  private final ApplicationCode application;
  private final AdminConfigApi.Operations operations;
  private final AdminSection section;
  private final boolean values;

  private AdminConfigModule(ApplicationCode application, boolean values) {
    this.application = application;
    this.values = values;
    this.operations = new AdminConfigApi.Operations(viewPermission(application));
    this.section =
        AdminSection.of("config", "Configuration", "/admin/config", viewPermission(application))
            .icon("i-lucide-sliders-horizontal")
            .description("The settings the application runs with, where they came from, and secret status.")
            .serving(operations.list, operations.get);
  }

  /**
   * The config section of an application.
   *
   * @param application the application code; the permission starts with it
   * @return the module, showing the values of settings that are safe to show
   */
  public static AdminConfigModule of(ApplicationCode application) {
    return new AdminConfigModule(application, true);
  }

  /**
   * The same module showing no values at all: state and source only.
   *
   * @return the module
   */
  public AdminConfigModule withoutValues() {
    return new AdminConfigModule(application, false);
  }

  /**
   * The permission that opens the section: {@code {app}:admin:config:view}.
   *
   * @param application the application code
   * @return the permission
   */
  public static String viewPermission(ApplicationCode application) {
    return application.value() + ":admin:config:view";
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, section);
  }

  @Override
  public void boot(App app) {
    Routes routes = app.get(Routes.class).forModule(ID);
    routes.bind(
        operations.list,
        r -> {
          List<SettingStatus> all = app.config().statuses();
          ConfigState state = r.query(AdminConfigApi.Operations.STATE);
          Boolean secret = r.query(AdminConfigApi.Operations.SECRET);
          String q = r.query(AdminConfigApi.Operations.Q);
          String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
          List<AdminConfigSetting> shown = new ArrayList<>();
          int set = 0;
          int defaulted = 0;
          int unset = 0;
          for (SettingStatus s : all) {
            switch (s.state()) {
              case SET -> set++;
              case DEFAULT -> defaulted++;
              case UNSET -> unset++;
            }
            if (state != null && !state.name().equals(s.state().name())) {
              continue;
            }
            if (secret != null && secret != s.secret()) {
              continue;
            }
            if (needle != null && !s.key().toLowerCase(Locale.ROOT).contains(needle) && !s.module().toLowerCase(Locale.ROOT).contains(needle)) {
              continue;
            }
            shown.add(AdminConfigApi.view(s, values));
          }
          HealthReport report = app.health().check();
          return Outcome.ok(
              new AdminConfig(
                  app.config().environment(),
                  shown.size(),
                  set,
                  defaulted,
                  unset,
                  shown,
                  new AdminHealth(report.isReady(), AdminApi.checks(report))));
        });
    routes.bind(
        operations.get,
        r -> {
          String key = r.path(AdminConfigApi.Operations.KEY);
          for (SettingStatus s : app.config().statuses()) {
            if (s.key().equals(key)) {
              return Outcome.ok(AdminConfigApi.view(s, values));
            }
          }
          throw new HttpException(404, "SETTING_NOT_FOUND", "no setting '" + key + "' is declared");
        });
  }
}
