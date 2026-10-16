package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminSection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The definitions the admin sections need on the platform (M4e): every permission the registered
 * sections require, and one suggested role, {@code admin} (on the platform {@code {app}:admin}),
 * that grants all of them. The permissions are listed one by one: the sync accepts no wildcard in a
 * role. Permissions reach the platform only through roles, so the role is what makes them exist.
 *
 * <p>An application that declares a role named {@code admin} itself keeps it as declared and gets
 * nothing added: it owns the decision. With no admin sections registered nothing is added.
 */
final class AdminDefinitions {

  /** The module name problems and duplicates are attributed to. */
  static final String MODULE = "admin";

  private AdminDefinitions() {}

  /**
   * The contributions to add to the catalog.
   *
   * @param sections the registered admin sections
   * @param declared what the modules declared
   * @return permissions and the role, or nothing
   */
  static List<Definitions.Contribution> contributions(
      List<AdminSection> sections, List<Definitions.Contribution> declared) {
    if (sections.isEmpty()) {
      return List.of();
    }
    for (Definitions.Contribution c : declared) {
      if (c.definition() instanceof Definition.Role r && r.name().equals("admin")) {
        return List.of();
      }
    }
    Set<String> alreadyDeclared = new java.util.HashSet<>();
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
    out.add(
        new Definitions.Contribution(
            MODULE,
            new Definition.Role(
                "admin",
                List.copyOf(permissions),
                "Administrator",
                "Opens the application's admin area and every section of it.")));
    return List.copyOf(out);
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
