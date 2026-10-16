package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminApi;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminApi.AdminEventType;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminApi.AdminEventTypeDetail;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminApi.AdminRole;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminApi.AdminRoleDetail;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminApi.AdminSpecVersion;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminApi.EventTypeStatus;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin sections projected from the platform (M4e slice 2): the application's <b>event types</b>
 * and <b>roles</b>, read-only, through the application's own service account ({@link
 * PlatformClient}) after the framework has checked the signed-in user's admin permission (admin
 * contract section 9, decision 4). List it after {@code AdminModule} and {@code FlowCatalystModule}.
 *
 * <p>Permissions: {@code {app}:admin:event-types:view} and {@code {app}:admin:roles:view}. Only the
 * application's own event types and roles are ever returned, whatever the platform sends; asking for
 * another application's record answers {@code 404}. A platform refusal or outage answers {@code 502
 * PLATFORM_ERROR} with a message that names no host or token (the cause is logged); a disabled
 * FlowCatalyst module answers {@code 503 PLATFORM_NOT_CONFIGURED}.
 */
public final class PlatformAdminModule implements Module {

  /** The module id. */
  public static final String ID = "admin-platform";

  private static final Logger LOG = LoggerFactory.getLogger(PlatformAdminModule.class);

  private final ApplicationCode application;
  private final PlatformAdminApi.Operations operations;
  private final AdminSection eventTypes;
  private final AdminSection roles;

  private PlatformAdminModule(ApplicationCode application) {
    this.application = application;
    String eventTypesView = eventTypesView(application);
    String rolesView = rolesView(application);
    this.operations = new PlatformAdminApi.Operations(eventTypesView, rolesView);
    this.eventTypes =
        AdminSection.of("event-types", "Event types", "/admin/event-types", eventTypesView)
            .icon("i-lucide-zap")
            .description("The event types this application publishes, as the platform holds them.")
            .serving(operations.listEventTypes, operations.getEventType);
    this.roles =
        AdminSection.of("roles", "Roles", "/admin/roles", rolesView)
            .icon("i-lucide-key-round")
            .description("This application's roles and the permissions each grants.")
            .serving(operations.listRoles, operations.getRole);
  }

  /**
   * The module for an application.
   *
   * @param application the application code; the permissions start with it and it must equal {@code
   *     flowcatalyst.application}
   * @return the module
   */
  public static PlatformAdminModule of(ApplicationCode application) {
    return new PlatformAdminModule(application);
  }

  /**
   * The permission that opens the event types section.
   *
   * @param application the application code
   * @return {@code {app}:admin:event-types:view}
   */
  public static String eventTypesView(ApplicationCode application) {
    return application.value() + ":admin:event-types:view";
  }

  /**
   * The permission that opens the roles section.
   *
   * @param application the application code
   * @return {@code {app}:admin:roles:view}
   */
  public static String rolesView(ApplicationCode application) {
    return application.value() + ":admin:roles:view";
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, eventTypes, roles);
  }

  @Override
  public void boot(App app) {
    PlatformClient client = app.get(PlatformClient.class);
    if (client.isEnabled() && !client.application().equals(application)) {
      throw new IllegalStateException(
          "PlatformAdminModule is for application '"
              + application.value()
              + "' but flowcatalyst.application is '"
              + client.application().value()
              + "'");
    }
    Routes routes = app.get(Routes.class).forModule(ID);
    String own = application.value();

    routes.bind(
        operations.listEventTypes,
        r -> {
          EventTypeStatus status = r.query(PlatformAdminApi.ET_STATUS);
          String subdomain = r.query(PlatformAdminApi.ET_SUBDOMAIN);
          String aggregate = r.query(PlatformAdminApi.ET_AGGREGATE);
          String q = lower(r.query(PlatformAdminApi.Q));
          List<PlatformClient.EventTypeInfo> found =
              platform(client, () -> client.eventTypes(own, status == null ? null : status.name(), subdomain, aggregate));
          List<AdminEventType> items = new ArrayList<>();
          for (PlatformClient.EventTypeInfo e : found) {
            if (!e.application().equals(own) || !matches(q, e.code(), e.name(), e.description())) {
              continue;
            }
            items.add(eventType(e));
          }
          items.sort(Comparator.comparing(AdminEventType::code));
          String after = r.query(PlatformAdminApi.AFTER);
          int limit = r.query(PlatformAdminApi.LIMIT);
          return Outcome.ok(AdminApi.page(items, AdminEventType::code, after, limit));
        });

    routes.bind(
        operations.getEventType,
        r -> {
          PlatformClient.EventTypeInfo e = platformOrNotFound(client, () -> client.eventType(r.path(PlatformAdminApi.ID)), "EVENT_TYPE_NOT_FOUND", "there is no such event type");
          if (!e.application().equals(own)) {
            throw new HttpException(404, "EVENT_TYPE_NOT_FOUND", "there is no such event type");
          }
          List<AdminSpecVersion> versions = new ArrayList<>();
          for (PlatformClient.SpecVersion v : e.specVersions()) {
            versions.add(new AdminSpecVersion(v.version(), v.status(), v.schema()));
          }
          return Outcome.ok(new AdminEventTypeDetail(eventType(e), List.copyOf(versions)));
        });

    routes.bind(
        operations.listRoles,
        r -> {
          String source = r.query(PlatformAdminApi.ROLE_SOURCE);
          String q = lower(r.query(PlatformAdminApi.Q));
          List<AdminRole> items = new ArrayList<>();
          for (PlatformClient.RoleInfo role : platform(client, client::roles)) {
            if (!ownsRole(own, role)) {
              continue;
            }
            if (source != null && !source.equalsIgnoreCase(role.source())) {
              continue;
            }
            if (!matches(q, role.name(), role.displayName(), role.description())) {
              continue;
            }
            items.add(role(role));
          }
          items.sort(Comparator.comparing(AdminRole::name));
          String after = r.query(PlatformAdminApi.AFTER);
          int limit = r.query(PlatformAdminApi.LIMIT);
          return Outcome.ok(AdminApi.page(items, AdminRole::name, after, limit));
        });

    routes.bind(
        operations.getRole,
        r -> {
          String name = r.path(PlatformAdminApi.NAME);
          if (!name.startsWith(own + ":")) {
            throw new HttpException(404, "ROLE_NOT_FOUND", "there is no such role");
          }
          PlatformClient.RoleInfo role = platformOrNotFound(client, () -> client.role(name), "ROLE_NOT_FOUND", "there is no such role");
          if (!ownsRole(own, role)) {
            throw new HttpException(404, "ROLE_NOT_FOUND", "there is no such role");
          }
          List<String> permissions = new ArrayList<>(role.permissions());
          permissions.sort(Comparator.naturalOrder());
          return Outcome.ok(new AdminRoleDetail(role(role), List.copyOf(permissions)));
        });
  }

  private static boolean ownsRole(String own, PlatformClient.RoleInfo role) {
    String owner = role.application().isBlank() ? role.name().split(":", 2)[0] : role.application();
    return owner.equals(own) && role.name().startsWith(own + ":");
  }

  private static AdminEventType eventType(PlatformClient.EventTypeInfo e) {
    EventTypeStatus status = "ARCHIVED".equalsIgnoreCase(e.status()) ? EventTypeStatus.ARCHIVED : EventTypeStatus.CURRENT;
    return new AdminEventType(
        e.id(), e.code(), e.name(), blankToNull(e.description()), e.subdomain(), e.aggregate(), e.event(), status, e.specVersions().size());
  }

  private static AdminRole role(PlatformClient.RoleInfo r) {
    return new AdminRole(r.id(), r.name(), r.displayName(), blankToNull(r.description()), r.source(), r.clientManaged(), r.permissions().size());
  }

  private static @Nullable String blankToNull(@Nullable String text) {
    return text == null || text.isBlank() ? null : text;
  }

  private static @Nullable String lower(@Nullable String text) {
    return text == null || text.isBlank() ? null : text.toLowerCase(Locale.ROOT);
  }

  private static boolean matches(@Nullable String needle, @Nullable String... fields) {
    if (needle == null) {
      return true;
    }
    for (String f : fields) {
      if (f != null && f.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  /** Runs a platform read; a failure is the standard 502 (or 503 when there is no connection). */
  private static <T> T platform(PlatformClient client, Supplier<T> read) {
    return platformOrNotFound(client, read, null, null);
  }

  private static <T> T platformOrNotFound(
      PlatformClient client, Supplier<T> read, @Nullable String notFoundCode, @Nullable String notFoundMessage) {
    if (!client.isEnabled()) {
      throw new HttpException(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection (flowcatalyst.enabled is false)");
    }
    try {
      return read.get();
    } catch (PlatformException e) {
      if (e.kind() == PlatformException.Kind.NOT_FOUND && notFoundCode != null) {
        throw new HttpException(404, notFoundCode, notFoundMessage == null ? "not found" : notFoundMessage);
      }
      // The cause can name hosts and request details: it goes to the log, not to the browser.
      LOG.warn("an admin read of the platform failed ({}): {}", e.kind(), e.getMessage());
      if (e.kind() == PlatformException.Kind.FORBIDDEN) {
        throw new HttpException(
            502,
            "PLATFORM_ERROR",
            "the platform refused this read for the application's service account; it needs the platform's view permission for this resource");
      }
      throw new HttpException(502, "PLATFORM_ERROR", "the platform could not be reached or answered with an error");
    }
  }
}
