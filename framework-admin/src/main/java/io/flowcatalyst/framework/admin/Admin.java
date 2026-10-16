package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Declarations;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The registry of admin sections (admin contract section 2). A module registers its sections in
 * {@code register}, like {@code OpenApi.declare}, so the {@code openapi} command sees them without
 * configuration; registering also declares each section's operations in the OpenAPI document. The
 * handlers are bound in {@code boot} with {@code Routes.bind}.
 *
 * <pre>{@code
 * // register(AppBuilder app)
 * Admin.section(app, AdminSection.of("users", "Users", "/admin/users", "acme:admin:users:view")
 *     .icon("i-lucide-users").serving(LIST_USERS));
 * }</pre>
 */
public final class Admin {

  private Admin() {}

  /**
   * Registers sections and declares their operations.
   *
   * @param app the builder, inside {@code register}
   * @param sections the sections, in navigation order
   */
  public static void section(AppBuilder app, AdminSection... sections) {
    for (AdminSection section : sections) {
      app.contribute(AdminSection.class, section);
      if (!section.operations().isEmpty()) {
        OpenApi.declare(app, section.operations().toArray(Operation<?, ?>[]::new));
      }
    }
  }

  /**
   * The registered sections.
   *
   * @param lookup the service lookup
   * @return the sections in registration order
   */
  public static List<AdminSection> sections(Lookup lookup) {
    return lookup.contributions(AdminSection.class);
  }

  /**
   * The registered sections, without starting anything (a test, the {@code openapi} command).
   *
   * @param declarations what modules declared in {@code register}
   * @return the sections in registration order
   */
  public static List<AdminSection> sections(Declarations declarations) {
    return declarations.contributions(AdminSection.class);
  }

  /**
   * The sections a caller may use: those whose permission the context holds (the platform's
   * wildcard rule). This is layer 2 of the permission model (admin contract section 3).
   *
   * @param sections the registered sections
   * @param context the caller's context
   * @return the permitted sections, in the same order
   */
  public static List<AdminSection> visibleTo(List<AdminSection> sections, Context context) {
    List<AdminSection> out = new ArrayList<>();
    for (AdminSection section : sections) {
      if (context.hasPermission(section.permission())) {
        out.add(section);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Every permission the sections and their operations require, sorted: what the application
   * declares to the platform (as {@code Definitions.permission}) and grants through a role.
   *
   * @param sections the registered sections
   * @return the permissions
   */
  public static Set<String> permissions(List<AdminSection> sections) {
    Set<String> out = new TreeSet<>();
    for (AdminSection section : sections) {
      out.add(section.permission());
      for (Operation<?, ?> op : section.operations()) {
        String p = op.permission();
        if (p != null) {
          out.add(p);
        }
      }
    }
    return out;
  }

  /**
   * The problems that need the whole registry (admin contract section 2, rules 2 and 3).
   *
   * @param sections the registered sections
   * @param applicationCode the application the admin module serves; every permission starts with it
   * @return the problems, empty when the registry is sound
   */
  public static List<Problem> problems(List<AdminSection> sections, String applicationCode) {
    List<Problem> out = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Set<String> paths = new HashSet<>();
    for (AdminSection section : sections) {
      if (!ids.add(section.id())) {
        out.add(Problem.of("admin", "section " + section.id(), "admin section id '" + section.id() + "' is registered twice"));
      }
      if (!paths.add(section.path())) {
        out.add(Problem.of("admin", "path " + section.path(), "admin path '" + section.path() + "' is used by two sections"));
      }
    }
    for (String permission : permissions(sections)) {
      if (!permission.startsWith(applicationCode + ":")) {
        out.add(
            Problem.of(
                "admin",
                "permission " + permission,
                "admin permission '" + permission + "' does not belong to application '" + applicationCode + "'"));
      }
    }
    return out;
  }
}
