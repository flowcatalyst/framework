package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.AdminApi.Page;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.TypeRef;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire types and operations of the admin sections projected from the platform (admin contract
 * section 11): the application's event types and roles, read-only.
 */
public final class PlatformAdminApi {
  private PlatformAdminApi() {}

  /** Whether an event type is in use. */
  public enum EventTypeStatus {
    /** In use. */
    CURRENT,
    /** Archived: no new events. */
    ARCHIVED
  }

  /**
   * An event type in a list.
   *
   * @param id the platform id
   * @param code {@code {application}:{subdomain}:{aggregate}:{event}}
   * @param name the display name
   * @param description what it means, absent when none
   * @param subdomain the subdomain
   * @param aggregate the aggregate
   * @param event the event name
   * @param status current or archived
   * @param versionCount how many specification versions it has
   */
  public record AdminEventType(
      String id,
      String code,
      String name,
      @Nullable String description,
      String subdomain,
      String aggregate,
      String event,
      EventTypeStatus status,
      int versionCount) {}

  /**
   * One version of an event type's payload specification.
   *
   * @param version the version
   * @param status the version's status
   * @param schema the schema as JSON text, absent when none
   */
  public record AdminSpecVersion(String version, String status, @Nullable String schema) {}

  /**
   * An event type with its specification versions.
   *
   * @param eventType the event type
   * @param versions the specification versions
   */
  public record AdminEventTypeDetail(AdminEventType eventType, List<AdminSpecVersion> versions) {}

  /**
   * A role in a list.
   *
   * @param id the platform id
   * @param name the full name, {@code {application}:{role}}
   * @param displayName the display name
   * @param description what it is for, absent when none
   * @param source where the platform learned of the role: CODE, DATABASE or SDK
   * @param clientManaged whether it is managed at client scope
   * @param permissionCount how many permissions it grants
   */
  public record AdminRole(
      String id,
      String name,
      String displayName,
      @Nullable String description,
      String source,
      boolean clientManaged,
      int permissionCount) {}

  /**
   * A role with the permissions it grants.
   *
   * @param role the role
   * @param permissions the permissions, sorted
   */
  public record AdminRoleDetail(AdminRole role, List<String> permissions) {}

  static final QueryParam<@Nullable EventTypeStatus> ET_STATUS = QueryParam.enumOf("status", EventTypeStatus.class).optional();
  static final QueryParam<@Nullable String> ET_SUBDOMAIN = QueryParam.string("subdomain").optional();
  static final QueryParam<@Nullable String> ET_AGGREGATE = QueryParam.string("aggregate").optional();
  static final QueryParam<@Nullable String> Q = QueryParam.string("q").optional();
  static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(50);
  static final QueryParam<@Nullable String> AFTER = QueryParam.string("after").optional();
  static final QueryParam<@Nullable String> ROLE_SOURCE = QueryParam.string("source").optional();
  static final PathParam<String> ID = PathParam.string("id");
  static final PathParam<String> NAME = PathParam.string("name");

  /** The operations for one application. */
  static final class Operations {
    final Operation<NoBody, Page<AdminEventType>> listEventTypes;
    final Operation<NoBody, AdminEventTypeDetail> getEventType;
    final Operation<NoBody, Page<AdminRole>> listRoles;
    final Operation<NoBody, AdminRoleDetail> getRole;

    Operations(String eventTypesView, String rolesView) {
      listEventTypes =
          Operation.get("/api/admin/event-types", new TypeRef<Page<AdminEventType>>() {})
              .id("listAdminEventTypes")
              .summary("The application's event types, as the platform holds them")
              .tag("admin")
              .query(ET_STATUS, ET_SUBDOMAIN, ET_AGGREGATE, Q, LIMIT, AFTER)
              .permission(eventTypesView)
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      getEventType =
          Operation.get("/api/admin/event-types/{id}", AdminEventTypeDetail.class)
              .id("getAdminEventType")
              .summary("One event type with its specification versions")
              .tag("admin")
              .path(ID)
              .permission(eventTypesView)
              .error(404, "EVENT_TYPE_NOT_FOUND", "there is no such event type of this application")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      listRoles =
          Operation.get("/api/admin/roles", new TypeRef<Page<AdminRole>>() {})
              .id("listAdminRoles")
              .summary("The application's roles, as the platform holds them")
              .tag("admin")
              .query(ROLE_SOURCE, Q, LIMIT, AFTER)
              .permission(rolesView)
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      getRole =
          Operation.get("/api/admin/roles/{name}", AdminRoleDetail.class)
              .id("getAdminRole")
              .summary("One role with its permissions")
              .tag("admin")
              .path(NAME)
              .permission(rolesView)
              .error(404, "ROLE_NOT_FOUND", "there is no such role of this application")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
    }
  }
}
