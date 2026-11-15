package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.auth.Principal;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.Problem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What start-up and the sync check about client-managed roles (admin contract section 3a, rule 4):
 * a role that a client's own administrators may assign must not carry any permission of an
 * operators-only admin section, by its definition or through the application's permission
 * mapping. The mapping is run for a principal that holds only the role, so a mapping that depends
 * on anything else about the principal cannot be checked; wildcards are matched by the platform's
 * rule.
 */
final class OperatorsOnlyGuard {

  /** A guard that checks nothing (no admin sections). */
  static final OperatorsOnlyGuard NONE = new OperatorsOnlyGuard(List.of(), null, "");

  private static final String MODULE = "flowcatalyst";

  private final List<AdminSection> sections;
  private final @Nullable PermissionMapper mapping;
  private final String application;

  /**
   * Creates a guard.
   *
   * @param sections the registered admin sections
   * @param mapping the application's permission mapping, or null when it has not given one
   * @param application the application code the roles belong to ({@code {application}:{role}})
   */
  OperatorsOnlyGuard(List<AdminSection> sections, @Nullable PermissionMapper mapping, String application) {
    this.sections = List.copyOf(sections);
    this.mapping = mapping;
    this.application = application;
  }

  /**
   * A guard for the registered sections; the application is the one the framework's own {@code
   * about} section (or, failing that, the first section) belongs to.
   *
   * @param sections the registered admin sections
   * @param mapping the application's permission mapping, or null
   * @return the guard; {@link #NONE} when there are no sections
   */
  static OperatorsOnlyGuard of(List<AdminSection> sections, @Nullable PermissionMapper mapping) {
    String application = "";
    for (AdminSection s : sections) {
      application = s.permission().substring(0, s.permission().indexOf(':'));
      if (s.id().equals("about")) {
        break;
      }
    }
    return sections.isEmpty() ? NONE : new OperatorsOnlyGuard(sections, mapping, application);
  }

  /** The console permission of the admin area, or null without admin sections. */
  @Nullable String consoleView() {
    return sections.isEmpty() ? null : application + ":admin:console:view";
  }

  /**
   * For every client-managed role, each permission of an operators-only section that its
   * definition or the permission mapping gives it, with the role and the permission named.
   *
   * @param roles the roles, each with the module that contributed it
   * @return the problems, empty when none
   */
  List<Problem> problems(List<Definitions.Contribution> roles) {
    List<Problem> out = new ArrayList<>();
    if (Admin.operatorsOnlyPermissions(sections).isEmpty()) {
      return out;
    }
    for (Definitions.Contribution c : roles) {
      if (!(c.definition() instanceof Definition.Role role) || !role.clientManaged()) {
        continue;
      }
      String full = application + ":" + role.name();
      Set<String> reported = new HashSet<>();
      problemsVia("the role's definition", role.permissions(), c.module(), role, full, reported, out);
      PermissionMapper m = mapping;
      if (m != null) {
        problemsVia("the application's permission mapping", mapped(m, full), c.module(), role, full, reported, out);
      }
    }
    return out;
  }

  /**
   * The same check for a catalog's roles (the sync holds itself to the refusal of start-up).
   *
   * @param catalog the validated definitions
   * @return the problems, empty when none
   */
  List<Problem> problems(DefinitionCatalog catalog) {
    return problems(
        catalog.roles().stream().map(r -> new Definitions.Contribution(MODULE, r)).collect(Collectors.toList()));
  }

  private void problemsVia(String via, Collection<String> held, String module, Definition.Role role, String full, Set<String> reported, List<Problem> out) {
    for (String permission : Admin.operatorsOnlyConflicts(sections, held)) {
      if (!reported.add(permission)) {
        continue;
      }
      out.add(
          Problem.of(
              module,
              "role " + role.name(),
              "role '"
                  + full
                  + "' is client-managed (a client's own administrators may assign it) but "
                  + via
                  + " grants the operators-only admin permission '"
                  + permission
                  + "'"
                  + matchingNote(held, permission)
                  + "; a partition holds the rows of every client. Give the permission to a role that is not client-managed"));
    }
  }

  /** Which held pattern grants the permission when it is not the permission itself: " (through 'a:b:*:*')". */
  private String matchingNote(Collection<String> held, String permission) {
    if (held.contains(permission)) {
      return "";
    }
    for (String h : held) {
      if (Admin.operatorsOnlyConflicts(sections, List.of(h)).contains(permission)) {
        return " (through '" + h + "')";
      }
    }
    return "";
  }

  private static Set<String> mapped(PermissionMapper mapping, String fullRole) {
    Set<String> granted = mapping.permissions(onlyRole(fullRole));
    return granted == null ? Set.of() : granted;
  }

  private static Principal onlyRole(String fullRole) {
    return new Principal(new PrincipalId("operators-only-guard"), PrincipalKind.USER, null, null, null, null, List.of(), List.of(), List.of(fullRole), Set.of());
  }

  /**
   * The warning of an application that declined the suggested role when no declared role grants
   * the permission that opens the admin area, or null when one does. A role grants it by its
   * definition or through the permission mapping run for a principal that holds only that role,
   * under the platform's wildcard rule. It is a warning because the mapping may give the permission
   * to a role this application does not declare, which the framework cannot list.
   *
   * @param contributions what the modules declared
   * @return the warning, or null
   */
  @Nullable String consoleViewWarning(List<Definitions.Contribution> contributions) {
    String view = consoleView();
    if (view == null) {
      return null;
    }
    for (Definitions.Contribution c : contributions) {
      if (!(c.definition() instanceof Definition.Role role)) {
        continue;
      }
      if (grants(role.permissions(), view)) {
        return null;
      }
      PermissionMapper m = mapping;
      if (m != null && grants(mapped(m, application + ":" + role.name()), view)) {
        return null;
      }
    }
    return "flowcatalyst: withoutAdminRole() is set and no declared role grants '"
        + view
        + "', so nobody can open the admin area unless the application's permission mapping gives that permission"
        + " to a role declared elsewhere (for example a platform role)";
  }

  private static boolean grants(Collection<String> held, String permission) {
    return Context.of(ClientId.PLATFORM).withPermissions(Set.copyOf(held)).hasPermission(permission);
  }
}
