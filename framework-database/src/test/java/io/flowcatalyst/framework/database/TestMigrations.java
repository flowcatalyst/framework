package io.flowcatalyst.framework.database;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** A module contributing migration files written into a temporary directory. */
final class TestMigrations implements Module {

  private final String id;
  private final URLClassLoader loader;

  private TestMigrations(String id, URLClassLoader loader) {
    this.id = id;
    this.loader = loader;
  }

  /** Creates (or reuses) the directory {@code root/migrations} as the module's migration location. */
  static TestMigrations in(String id, Path root) throws IOException {
    Files.createDirectories(root.resolve("migrations"));
    try {
      return new TestMigrations(id, new URLClassLoader(new URL[] {root.toUri().toURL()}, null));
    } catch (MalformedURLException e) {
      throw new IOException(e);
    }
  }

  static void write(Path root, String fileName, String sql) throws IOException {
    Files.createDirectories(root.resolve("migrations"));
    Files.writeString(root.resolve("migrations").resolve(fileName), sql);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void register(AppBuilder app) {
    app.contribute(MigrationSet.class, new MigrationSet(id, loader, "migrations"));
  }
}
