package io.flowcatalyst.framework.sql;

import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.database.Migrations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The generator (checked-SQL contract, section 4) as a library: migrate a database, describe every
 * query of a project, produce the generated sources, write them or check them against what is
 * committed.
 */
public final class CheckedSql {

  private static final Pattern CLASS_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");
  private static final Pattern PACKAGE_PART = Pattern.compile("[a-z_][a-z0-9_]*");

  private CheckedSql() {}

  /**
   * Applies migration sets to the database with the framework's own runner.
   *
   * @param url the JDBC URL of an empty (throwaway) database
   * @param user the user
   * @param password the password
   * @param sets the sets, in the order to apply them
   */
  public static void migrate(String url, String user, String password, List<MigrationSet> sets) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setUrl(url);
    ds.setUser(user);
    ds.setPassword(password);
    Migrations.apply("checked-sql", ds, sets);
  }

  /**
   * Migrates the database, then generates every source.
   *
   * @param layout where the files are
   * @param url the JDBC URL of an empty (throwaway) database
   * @param user the user
   * @param password the password
   * @param sets the migration sets
   * @return the generated files: path below the output directory (forward slashes) to text
   * @throws GenerationFailures naming each file and line that could not be generated
   */
  public static Map<String, String> generate(
      Layout layout, String url, String user, String password, List<MigrationSet> sets) {
    migrate(url, user, password, sets);
    try (Connection c = DriverManager.getConnection(url, user, password)) {
      return generate(layout, c);
    } catch (SQLException e) {
      throw new IllegalStateException("cannot connect to " + url + ": " + e.getMessage(), e);
    }
  }

  /**
   * Describes every query of the project against the database and generates the sources.
   *
   * @param layout where the files are
   * @param connection a connection to the migrated database
   * @return the generated files: path below the output directory (forward slashes) to text,
   *     sorted by path
   * @throws GenerationFailures naming each file and line that could not be generated
   */
  public static Map<String, String> generate(Layout layout, Connection connection) {
    List<GenerationException> failures = new ArrayList<>();
    Map<String, String> out = new TreeMap<>();
    List<Path> files = sqlFiles(layout.sqlDir());
    for (Path path : files) {
      String relative = relative(layout.sqlDir(), path);
      String label = layout.sqlLabel() + "/" + relative;
      try {
        out.putAll(generateFile(layout, label, relative, read(path), connection, failures));
      } catch (GenerationException e) {
        failures.add(e);
      }
    }
    if (!failures.isEmpty()) {
      throw new GenerationFailures(failures);
    }
    return out;
  }

  private static Map<String, String> generateFile(
      Layout layout,
      String label,
      String relative,
      String content,
      Connection connection,
      List<GenerationException> failures) {
    int slash = relative.lastIndexOf('/');
    if (slash < 0) {
      throw new GenerationException(
          label, 0, "must be in a directory below " + layout.sqlLabel() + " that names its package");
    }
    String dir = relative.substring(0, slash);
    for (String part : dir.split("/")) {
      if (!PACKAGE_PART.matcher(part).matches()) {
        throw new GenerationException(
            label, 0, "directory '" + part + "' is not a valid package name (lower case letters, digits, _)");
      }
    }
    String className = relative.substring(slash + 1, relative.length() - ".sql".length());
    if (!CLASS_NAME.matcher(className).matches()) {
      throw new GenerationException(
          label, 0, "file name '" + className + "' is not a valid class name (UpperCamel)");
    }
    QueryFile parsed = QueryParser.parse(label, content);
    List<ResolvedQuery> resolved = new ArrayList<>();
    boolean failed = false;
    for (Query q : parsed.queries()) {
      try {
        resolved.add(QueryResolver.resolve(label, q, connection));
      } catch (GenerationException e) {
        failures.add(e);
        failed = true;
      }
    }
    if (failed) {
      return Map.of();
    }
    String packageName = dir.replace('/', '.');
    String source = JavaGenerator.generate(label, packageName, className, resolved);
    return Map.of(dir + "/" + className + ".java", source);
  }

  /**
   * Writes generated files, and removes generated files that no longer have a query file.
   *
   * @param layout where the files are
   * @param generated the output of {@link #generate}
   */
  public static void write(Layout layout, Map<String, String> generated) {
    try {
      for (Map.Entry<String, String> e : generated.entrySet()) {
        Path target = layout.outDir().resolve(e.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
      }
      for (String orphan : orphans(layout, generated)) {
        Files.delete(layout.outDir().resolve(orphan));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Compares generated output with the committed files.
   *
   * @param layout where the files are
   * @param generated the output of {@link #generate}, freshly made
   * @return one line per problem, naming the file; empty when everything is current
   */
  public static List<String> check(Layout layout, Map<String, String> generated) {
    List<String> problems = new ArrayList<>();
    try {
      for (Map.Entry<String, String> e : generated.entrySet()) {
        Path committed = layout.outDir().resolve(e.getKey());
        String shown = layout.outLabel() + "/" + e.getKey();
        if (!Files.isRegularFile(committed)) {
          problems.add(shown + ": missing; it has not been generated from its query file");
        } else if (!Files.readString(committed, StandardCharsets.UTF_8).equals(e.getValue())) {
          problems.add(shown + ": differs from what the query file generates (edited by hand, or a query or the schema changed)");
        }
      }
      for (String orphan : orphans(layout, generated)) {
        problems.add(layout.outLabel() + "/" + orphan + ": generated file with no query file");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return problems;
  }

  /** Generated files (by their header) in the output directory that are not in the output. */
  private static List<String> orphans(Layout layout, Map<String, String> generated) throws IOException {
    List<String> out = new ArrayList<>();
    if (!Files.isDirectory(layout.outDir())) {
      return out;
    }
    try (Stream<Path> walk = Files.walk(layout.outDir())) {
      for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
        String rel = relative(layout.outDir(), p);
        if (!rel.endsWith(".java") || generated.containsKey(rel)) {
          continue;
        }
        String text = Files.readString(p, StandardCharsets.UTF_8);
        if (text.startsWith(JavaGenerator.HEADER_PREFIX)) {
          out.add(rel);
        }
      }
    }
    return out;
  }

  private static List<Path> sqlFiles(Path sqlDir) {
    if (!Files.isDirectory(sqlDir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(sqlDir)) {
      return walk.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".sql"))
          .sorted((a, b) -> relative(sqlDir, a).compareTo(relative(sqlDir, b)))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
