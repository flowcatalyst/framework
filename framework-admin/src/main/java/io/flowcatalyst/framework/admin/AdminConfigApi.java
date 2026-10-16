package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.admin.AdminApi.AdminCheck;
import io.flowcatalyst.framework.config.SettingStatus;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire types and operations of the config section (admin contract section 10): the
 * application's declared settings, where each value came from, and the status of its secrets. A
 * secret's value is never on the wire.
 */
public final class AdminConfigApi {
  private AdminConfigApi() {}

  /** Where the effective value comes from. */
  public enum ConfigState {
    /** A file or the environment gave a value. */
    SET,
    /** No source gave one; the declared default applies. */
    DEFAULT,
    /** There is no value. */
    UNSET
  }

  /**
   * One declared setting.
   *
   * @param key the dotted key
   * @param module the module that declared it
   * @param type the accepted shape, for example "an integer"
   * @param secret whether it is declared as a secret
   * @param required whether start-up fails without a value
   * @param state whether it has a value and whether that is the default
   * @param source {@code default}, {@code not set}, an environment variable or a file; never a value
   * @param value the value as text; absent for a secret, a credential-named key, an unset setting
   *     and when values are switched off
   * @param withheld true when there is a value that is deliberately not shown
   * @param description what it is for, absent when none was given
   */
  public record AdminConfigSetting(
      String key,
      String module,
      String type,
      boolean secret,
      boolean required,
      ConfigState state,
      String source,
      @Nullable String value,
      boolean withheld,
      @Nullable String description) {}

  /**
   * The health of the application, as the about page reports it.
   *
   * @param ready the decision {@code /health/ready} makes
   * @param checks every check, without failure messages
   */
  public record AdminHealth(boolean ready, List<AdminCheck> checks) {}

  /**
   * The settings that match, with the totals of all of them.
   *
   * @param environment {@code APP_ENV}
   * @param total how many settings match the filters
   * @param set how many of all settings have a value from a source
   * @param defaulted how many of all settings use their default
   * @param unset how many of all settings have no value
   * @param settings the matching settings, in declaration order
   * @param health readiness and the checks
   */
  public record AdminConfig(
      String environment,
      int total,
      int set,
      int defaulted,
      int unset,
      List<AdminConfigSetting> settings,
      AdminHealth health) {}

  static AdminConfigSetting view(SettingStatus s, boolean values) {
    boolean hidden = !values && s.value() != null;
    return new AdminConfigSetting(
        s.key(),
        s.module(),
        s.type(),
        s.secret(),
        s.required(),
        ConfigState.valueOf(s.state().name()),
        s.source(),
        hidden ? null : s.value(),
        s.withheld() || hidden,
        s.description().isBlank() ? null : s.description());
  }

  /** The config section's operations for one application. */
  static final class Operations {
    static final QueryParam<@Nullable ConfigState> STATE = QueryParam.enumOf("state", ConfigState.class).optional();
    static final QueryParam<@Nullable Boolean> SECRET = QueryParam.bool("secret").optional();
    static final QueryParam<@Nullable String> Q = QueryParam.string("q").optional();
    static final PathParam<String> KEY = PathParam.string("key");

    final Operation<NoBody, AdminConfig> list;
    final Operation<NoBody, AdminConfigSetting> get;

    Operations(String permission) {
      list =
          Operation.get("/api/admin/config", AdminConfig.class)
              .id("listAdminConfig")
              .summary("The application's settings, where they came from, and secret status")
              .tag("admin")
              .query(STATE, SECRET, Q)
              .permission(permission)
              .build();
      get =
          Operation.get("/api/admin/config/{key}", AdminConfigSetting.class)
              .id("getAdminConfigSetting")
              .summary("One setting")
              .tag("admin")
              .path(KEY)
              .permission(permission)
              .error(404, "SETTING_NOT_FOUND", "no such setting is declared")
              .build();
    }
  }
}
