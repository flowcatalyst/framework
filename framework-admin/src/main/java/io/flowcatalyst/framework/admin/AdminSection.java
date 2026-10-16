package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.http.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One section of the application's admin area (admin contract section 2): a navigation entry, the
 * permission that shows it, and the typed operations it serves. Construction enforces the start-up
 * rules that need no application code: every operation has a permission, permissions have four
 * segments, operations are under {@code /api/admin/} and the path is under {@code /admin}.
 *
 * @param id {@code [a-z][a-z0-9-]*}, unique; the UI's page registry uses the same id
 * @param title the navigation label
 * @param path the SPA route, under {@code /admin}
 * @param permission the permission that shows the section; four {@code :}-separated segments
 * @param icon a Lucide icon name for the navigation, or null
 * @param description one sentence, or null
 * @param operations the operations the section serves; each requires a permission
 */
public record AdminSection(
    String id,
    String title,
    String path,
    String permission,
    @Nullable String icon,
    @Nullable String description,
    List<Operation<?, ?>> operations) {

  private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Pattern PERMISSION = Pattern.compile("[^:\\s]+:[^:\\s]+:[^:\\s]+:[^:\\s]+");

  /** The prefix every admin operation lives under. */
  public static final String API_PREFIX = "/api/admin/";

  /** The prefix of every section's SPA route. */
  public static final String PATH_PREFIX = "/admin";

  /**
   * Validates and copies.
   *
   * @throws IllegalArgumentException when a start-up rule is broken; the message lists every problem
   */
  public AdminSection {
    operations = List.copyOf(operations);
    List<String> problems = new ArrayList<>();
    if (!ID.matcher(id).matches()) {
      problems.add("the id must match [a-z][a-z0-9-]*");
    }
    if (title.isBlank()) {
      problems.add("the title is blank");
    }
    if (!path.equals(PATH_PREFIX) && !path.startsWith(PATH_PREFIX + "/")) {
      problems.add("the path must be " + PATH_PREFIX + " or under it");
    }
    if (!PERMISSION.matcher(permission).matches()) {
      problems.add("the permission must have four ':'-separated segments");
    }
    for (Operation<?, ?> op : operations) {
      String operationPermission = op.permission();
      if (operationPermission == null) {
        problems.add("operation " + op.label() + " has no required permission");
      } else if (!PERMISSION.matcher(operationPermission).matches()) {
        problems.add("operation " + op.label() + ": the permission must have four ':'-separated segments");
      }
      if (!op.pattern().startsWith(API_PREFIX)) {
        problems.add("operation " + op.label() + " must be under " + API_PREFIX);
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException("admin section '" + id + "': " + String.join("; ", problems));
    }
  }

  /**
   * A section without an icon, a description or operations.
   *
   * @param id the id
   * @param title the navigation label
   * @param path the SPA route under {@code /admin}
   * @param permission the permission that shows the section
   * @return the section
   */
  public static AdminSection of(String id, String title, String path, String permission) {
    return new AdminSection(id, title, path, permission, null, null, List.of());
  }

  /**
   * Copies with an icon.
   *
   * @param value a Lucide icon name, for example {@code i-lucide-users}
   * @return the copy
   */
  public AdminSection icon(String value) {
    return new AdminSection(id, title, path, permission, value, description, operations);
  }

  /**
   * Copies with a description.
   *
   * @param value one sentence
   * @return the copy
   */
  public AdminSection description(String value) {
    return new AdminSection(id, title, path, permission, icon, value, operations);
  }

  /**
   * Copies with the operations the section serves (replacing any given before).
   *
   * @param value the operations; each requires a permission and lives under {@code /api/admin/}
   * @return the copy
   * @throws IllegalArgumentException when one has no permission
   */
  public AdminSection serving(Operation<?, ?>... value) {
    return new AdminSection(id, title, path, permission, icon, description, List.of(value));
  }
}
