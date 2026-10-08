package io.flowcatalyst.framework.database;

import io.flowcatalyst.framework.core.AppBuilder;
import java.util.regex.Pattern;

/**
 * A module's migrations: its module id and the classpath directory holding
 * {@code V<version>__<description>.sql} files.
 *
 * @param module the contributing module's id; the history table is keyed by it
 * @param loader the class loader that can see the directory
 * @param location the classpath directory, for example {@code db/migration/locks}
 */
public record MigrationSet(String module, ClassLoader loader, String location) {

  private static final Pattern LOCATION = Pattern.compile("[A-Za-z0-9._/-]+");

  /** Validates the location. */
  public MigrationSet {
    if (!LOCATION.matcher(location).matches() || location.startsWith("/")) {
      throw new IllegalArgumentException("invalid migration location: " + location);
    }
    location = location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
  }

  /**
   * Contributes a module's migrations. Call from {@code register}; the sets are applied in module
   * registration order, and within a set by version.
   *
   * @param app the builder, inside the contributing module's register hook
   * @param owner a class in the module's jar, whose class loader finds the files
   * @param location the classpath directory, for example {@code db/migration/locks}
   */
  public static void contribute(AppBuilder app, Class<?> owner, String location) {
    ClassLoader loader = owner.getClassLoader();
    if (loader == null) {
      throw new IllegalArgumentException(owner + " has no class loader");
    }
    app.contribute(MigrationSet.class, new MigrationSet(app.moduleId(), loader, location));
  }
}
