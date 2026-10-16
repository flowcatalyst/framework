package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire types and operations of the skeleton (admin contract section 4). The operations are
 * built per application because their permission carries the application code.
 */
public final class AdminApi {

  private AdminApi() {}

  /**
   * A section in the navigation.
   *
   * @param id the section id
   * @param title the navigation label
   * @param path the SPA route
   * @param permission the permission that shows the section
   * @param icon a Lucide icon name, absent when none
   * @param description one sentence, absent when none
   */
  public record AdminSectionView(
      String id, String title, String path, String permission, @Nullable String icon, @Nullable String description) {}

  /**
   * The sections the caller may use.
   *
   * @param sections in registration order
   */
  public record AdminNavigation(List<AdminSectionView> sections) {}

  /** The state of one check. */
  public enum AdminCheckStatus {
    /** The check passed. */
    UP,
    /** The check failed. */
    DOWN,
    /** The check did not answer in time. */
    TIMED_OUT
  }

  /**
   * One health check; the failure message is deliberately not on the wire.
   *
   * @param name the check's name
   * @param status its state
   * @param critical whether it decides readiness
   */
  public record AdminCheck(String name, AdminCheckStatus status, boolean critical) {}

  /**
   * What the application is and whether it is healthy.
   *
   * @param application the application code
   * @param framework the framework version, absent when unknown
   * @param environment {@code APP_ENV}
   * @param startedAt when the admin module started
   * @param ready the decision {@code /health/ready} makes
   * @param checks every check
   */
  public record AdminAbout(
      String application,
      @Nullable String framework,
      String environment,
      Instant startedAt,
      boolean ready,
      List<AdminCheck> checks) {}

  /** The skeleton's operations for one application. */
  static final class Operations {
    final Operation<NoBody, AdminNavigation> navigation;
    final Operation<NoBody, AdminAbout> about;

    Operations(String permission) {
      navigation =
          Operation.get("/api/admin/navigation", AdminNavigation.class)
              .id("getAdminNavigation")
              .summary("The admin sections the caller may use")
              .tag("admin")
              .permission(permission)
              .build();
      about =
          Operation.get("/api/admin/about", AdminAbout.class)
              .id("getAdminAbout")
              .summary("What the application is and whether it is healthy")
              .tag("admin")
              .permission(permission)
              .build();
    }
  }
}
