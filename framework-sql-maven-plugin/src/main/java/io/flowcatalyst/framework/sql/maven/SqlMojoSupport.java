package io.flowcatalyst.framework.sql.maven;

import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.sql.CheckedSql;
import io.flowcatalyst.framework.sql.GenerationFailures;
import io.flowcatalyst.framework.sql.Layout;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jspecify.annotations.Nullable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** What {@code generate} and {@code check} share: the layout, the database and the description. */
abstract class SqlMojoSupport extends AbstractMojo {

  /** The Maven project. */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  MavenProject project;

  /** Where the query files are; the path below it is the Java package. */
  @Parameter(property = "fw.sql.sqlDirectory", defaultValue = "${project.basedir}/src/main/sql")
  File sqlDirectory;

  /** Where the generated sources are written and committed. */
  @Parameter(
      property = "fw.sql.outputDirectory",
      defaultValue = "${project.basedir}/src/main/java-generated")
  File outputDirectory;

  /** The migration sets to apply to the scratch database, in order. */
  @Parameter List<Migration> migrations;

  /**
   * The JDBC URL of an empty database to use instead of a throwaway container. Falls back to the
   * environment variable {@code FW_SQL_DATABASE_URL}.
   */
  @Parameter(property = "fw.sql.databaseUrl")
  @Nullable String databaseUrl;

  /** The user for {@code databaseUrl}; falls back to {@code FW_SQL_DATABASE_USER}, then {@code postgres}. */
  @Parameter(property = "fw.sql.databaseUser")
  @Nullable String databaseUser;

  /** The password for {@code databaseUrl}; falls back to {@code FW_SQL_DATABASE_PASSWORD}. */
  @Parameter(property = "fw.sql.databasePassword")
  @Nullable String databasePassword;

  /** The image of the throwaway database. */
  @Parameter(property = "fw.sql.image", defaultValue = "postgres:18-alpine")
  String image;

  /** Skips the goal. */
  @Parameter(property = "fw.sql.skip", defaultValue = "false")
  boolean skip;

  Layout layout() {
    Path base = project.getBasedir().toPath();
    return new Layout(
        sqlDirectory.toPath(),
        label(base, sqlDirectory.toPath()),
        outputDirectory.toPath(),
        label(base, outputDirectory.toPath()));
  }

  private static String label(Path base, Path dir) {
    Path abs = dir.toAbsolutePath().normalize();
    Path root = base.toAbsolutePath().normalize();
    return (abs.startsWith(root) ? root.relativize(abs) : abs).toString().replace('\\', '/');
  }

  /** Describes every query and returns the generated files; the heart of both goals. */
  Map<String, String> describeAll(Layout layout) throws MojoExecutionException, MojoFailureException {
    if (!CheckedSql.hasQueryFiles(layout)) {
      return Map.of();
    }
    long started = System.nanoTime();
    try (URLClassLoader loader = projectClassLoader()) {
      List<MigrationSet> sets = new ArrayList<>();
      for (Migration m : migrations == null ? List.<Migration>of() : migrations) {
        if (m.moduleId().isBlank() || m.location().isBlank()) {
          throw new MojoExecutionException("each <migration> needs a <moduleId> and a <location>");
        }
        sets.add(new MigrationSet(m.moduleId(), loader, m.location()));
      }
      String url = firstNonBlank(databaseUrl, System.getenv("FW_SQL_DATABASE_URL"));
      Map<String, String> out;
      if (url != null) {
        String user = firstNonBlank(databaseUser, System.getenv("FW_SQL_DATABASE_USER"));
        String password = firstNonBlank(databasePassword, System.getenv("FW_SQL_DATABASE_PASSWORD"));
        getLog().info("Describing queries against " + url);
        out = CheckedSql.generate(layout, url, user == null ? "postgres" : user, password == null ? "" : password, sets);
      } else {
        out = describeInContainer(layout, sets);
      }
      getLog().info(
              "Described the queries of "
                  + out.size()
                  + " file(s) in "
                  + (System.nanoTime() - started) / 1_000_000
                  + " ms");
      return out;
    } catch (GenerationFailures e) {
      throw new MojoFailureException(
          "Cannot generate SQL code; " + e.failures().size() + " problem(s):\n" + e.getMessage(), e);
    } catch (StartupException e) {
      throw new MojoFailureException("Cannot apply the migrations:\n" + e.getMessage(), e);
    } catch (IOException e) {
      throw new MojoExecutionException("Cannot read the project's class path: " + e.getMessage(), e);
    }
  }

  private Map<String, String> describeInContainer(Layout layout, List<MigrationSet> sets)
      throws MojoExecutionException {
    PostgreSQLContainer container;
    try {
      container = new PostgreSQLContainer(DockerImageName.parse(image));
      container.start();
    } catch (RuntimeException e) {
      throw new MojoExecutionException(
          "Cannot start a throwaway PostgreSQL ("
              + image
              + "): "
              + e.getMessage()
              + ". Start Docker, or point FW_SQL_DATABASE_URL at an empty database.",
          e);
    }
    try {
      getLog().info("Describing queries against a throwaway " + image);
      return CheckedSql.generate(
          layout, container.getJdbcUrl(), container.getUsername(), container.getPassword(), sets);
    } finally {
      container.stop();
    }
  }

  /** The project's resources and its dependencies: where migration files are found. */
  private URLClassLoader projectClassLoader() throws MojoExecutionException {
    List<URL> urls = new ArrayList<>();
    try {
      for (Resource r : project.getResources()) {
        File dir = new File(r.getDirectory());
        urls.add((dir.isAbsolute() ? dir : new File(project.getBasedir(), r.getDirectory())).toURI().toURL());
      }
      // The project's own output directory holds copies of its resources (after an earlier
      // build), so reading it as well would find every migration twice.
      String own = new File(project.getBuild().getOutputDirectory()).getAbsoluteFile().toString();
      for (String element : project.getCompileClasspathElements()) {
        if (new File(element).getAbsoluteFile().toString().equals(own)) {
          continue;
        }
        urls.add(new File(element).toURI().toURL());
      }
    } catch (DependencyResolutionRequiredException | MalformedURLException e) {
      throw new MojoExecutionException("Cannot build the project's class path: " + e.getMessage(), e);
    }
    return new URLClassLoader(urls.toArray(new URL[0]), null);
  }

  private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return b != null && !b.isBlank() ? b : null;
  }

  void addSourceRoot() {
    project.addCompileSourceRoot(outputDirectory.getAbsolutePath());
  }
}
