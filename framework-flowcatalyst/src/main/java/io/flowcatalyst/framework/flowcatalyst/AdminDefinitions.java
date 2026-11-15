package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.core.Problem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The definitions the admin sections need on the platform (M4e): every permission the registered
 * sections require, and one suggested role, {@code admin} (on the platform {@code {app}:admin}),
 * that grants all of them. The permissions are listed one by one: the sync accepts no wildcard in a
 * role. Permissions reach the platform only through roles, so the role is what makes them exist.
 *
 * <p>The role can be named or declined (admin contract section 3): {@link
 * FlowCatalystModule#adminRoleName}, {@link FlowCatalystModule#adminRoleDisplayName} and {@link
 * FlowCatalystModule#withoutAdminRole}. An application that declares a role of the chosen name
 * itself keeps it as declared and gets nothing added: it owns the decision. With no admin sections
 * registered nothing is added.
 */
final class AdminDefinitions {

  /** The module name problems and duplicates are attributed to. */
  static final String MODULE = "admin";

  private AdminDefinitions() {}

  /**
   * How the suggested role is declared.
   *
   * @param roleName the chosen role name, or null for {@code admin}
   * @param displayName the chosen display name, or null to derive it
   * @param withoutRole whether the role is declined (only the permissions are defined)
   */
  record Options(@Nullable String roleName, @Nullable String displayName, boolean withoutRole) {

    /** The defaults: the role {@code admin}, displayed as {@code Administrator}. */
    static final Options DEFAULTS = new Options(null, null, false);

    /** The role's name. */
    String name() {
      return roleName == null ? "admin" : roleName;
    }
  }

  /**
   * The problems of the options themselves: declining the role and also naming it.
   *
   * @param options the options
   * @return the problems, empty when none
   */
  static List<Problem> problems(Options options) {
    if (options.withoutRole() && (options.roleName() != null || options.displayName() != null)) {
      return List.of(
          Problem.of(
              MODULE,
              "AdminDefinitions",
              "withoutAdminRole() adds no role, so adminRoleName and adminRoleDisplayName have no effect; use one or the other"));
    }
    return List.of();
  }

  /**
   * The contributions to add to the catalog.
   *
   * @param sections the registered admin sections
   * @param declared what the modules declared
   * @param options how the role is declared
   * @return permissions and the role, or nothing
   */
  static List<Definitions.Contribution> contributions(
      List<AdminSection> sections, List<Definitions.Contribution> declared, Options options) {
    if (sections.isEmpty()) {
      return List.of();
    }
    String name = options.name();
    for (Definitions.Contribution c : declared) {
      if (!options.withoutRole() && c.definition() instanceof Definition.Role r && r.name().equals(name)) {
        return List.of();
      }
    }
    Set<String> alreadyDeclared = new HashSet<>();
    for (Definitions.Contribution c : declared) {
      if (c.definition() instanceof Definition.Permission p) {
        alreadyDeclared.add(p.code());
      }
    }
    Map<String, String> titles = new HashMap<>();
    for (AdminSection s : sections) {
      titles.put(s.id(), s.title());
    }
    Set<String> permissions = Admin.permissions(sections);
    List<Definitions.Contribution> out = new ArrayList<>();
    for (String code : permissions) {
      if (!alreadyDeclared.contains(code)) {
        out.add(new Definitions.Contribution(MODULE, new Definition.Permission(code, describe(code, titles))));
      }
    }
    if (options.withoutRole()) {
      return List.copyOf(out);
    }
    String display = options.displayName() != null ? options.displayName() : displayName(name);
    out.add(
        new Definitions.Contribution(
            MODULE,
            new Definition.Role(
                name,
                List.copyOf(permissions),
                display,
                "Opens the application's admin area and every section of it.")));
    return List.copyOf(out);
  }

  /** {@code Administrator} for {@code admin}; otherwise the name with hyphens as spaces and the first letter upper-case. */
  static String displayName(String name) {
    if (name.equals("admin")) {
      return "Administrator";
    }
    String text = name.replace('-', ' ');
    if (text.isEmpty()) {
      return text;
    }
    int first = text.codePointAt(0);
    return new String(Character.toChars(Character.toUpperCase(first))) + text.substring(Character.charCount(first));
  }

  private static String describe(String code, Map<String, String> titles) {
    String[] parts = code.split(":");
    String section = parts.length > 2 ? parts[2] : "";
    String action = parts.length > 3 ? parts[3] : "";
    if (section.equals("console")) {
      return "Open the admin area";
    }
    String title = titles.getOrDefault(section, section);
    return switch (action) {
      case "view" -> "View " + title.toLowerCase(Locale.ROOT) + " in the admin area";
      case "manage" -> "Manage " + title.toLowerCase(Locale.ROOT) + " in the admin area";
      default -> action + " " + title.toLowerCase(Locale.ROOT) + " in the admin area";
    };
  }
}
