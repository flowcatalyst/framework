package io.flowcatalyst.framework.database;

import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies migration sets to one database (database contract, section 2).
 *
 * <p>The whole run holds a PostgreSQL advisory lock, so instances starting together apply each
 * file once. Everything is checked before anything is applied; each file then runs in its own
 * transaction together with its history row.
 */
final class MigrationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);

  /** Session-level advisory lock key: the bytes of "FW_MIGRA". */
  static final long ADVISORY_LOCK_KEY = 0x46575F4D49475241L;

  private static final Pattern FILE = Pattern.compile("V(\\d+)__(.+)\\.sql");

  private static final String CREATE_HISTORY =
      """
      CREATE TABLE IF NOT EXISTS fw_schema_history (
          module      text        NOT NULL,
          version     int         NOT NULL,
          description text        NOT NULL,
          checksum    text        NOT NULL,
          applied_at  timestamptz NOT NULL,
          PRIMARY KEY (module, version)
      )""";

  private record Applied(int version, String description, String checksum) {}

  private record MigrationFile(
      String module, int version, String description, String fileName, String checksum, String sql) {}

  private final String databaseName;
  private final DataSource dataSource;
  private final List<MigrationSet> sets;
  private final List<Problem> problems = new ArrayList<>();

  MigrationRunner(String databaseName, DataSource dataSource, List<MigrationSet> sets) {
    this.databaseName = databaseName;
    this.dataSource = dataSource;
    this.sets = sets;
  }

  /**
   * Runs the migrations.
   *
   * @throws StartupException naming the module, file and database error
   */
  void run() {
    // Read the files first: nothing is locked while the classpath is being read.
    Map<String, List<MigrationFile>> files = new HashMap<>();
    List<String> moduleOrder = new ArrayList<>();
    for (MigrationSet set : sets) {
      if (!moduleOrder.contains(set.module())) {
        moduleOrder.add(set.module());
      }
      files.computeIfAbsent(set.module(), m -> new ArrayList<>()).addAll(readSet(set));
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }

    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(true);
      lock(c);
      try {
        migrate(c, moduleOrder, files);
      } finally {
        unlock(c);
      }
    } catch (SQLException e) {
      throw new StartupException(
          List.of(
              new Problem(
                  null,
                  "database:" + databaseName,
                  null,
                  "migrations failed: " + e.getMessage(),
                  e)));
    }
  }

  private void migrate(
      Connection c, List<String> moduleOrder, Map<String, List<MigrationFile>> files)
      throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(CREATE_HISTORY);
    }
    Map<String, Map<Integer, Applied>> applied = readHistory(c);

    // Validate everything before applying anything.
    List<MigrationFile> pending = new ArrayList<>();
    for (String module : moduleOrder) {
      List<MigrationFile> moduleFiles = files.getOrDefault(module, List.of());
      checkDuplicates(module, moduleFiles);
      Map<Integer, Applied> done = applied.getOrDefault(module, Map.of());
      Map<Integer, MigrationFile> byVersion = new TreeMap<>();
      for (MigrationFile f : moduleFiles) {
        byVersion.putIfAbsent(f.version(), f);
      }
      int highestApplied = done.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
      for (Map.Entry<Integer, Applied> e : new TreeMap<>(done).entrySet()) {
        MigrationFile f = byVersion.get(e.getKey());
        if (f == null) {
          problems.add(
              Problem.of(
                  module,
                  "V" + e.getKey(),
                  "version "
                      + e.getKey()
                      + " ("
                      + e.getValue().description()
                      + ") was applied but its file is gone"));
        } else if (!f.checksum().equals(e.getValue().checksum())) {
          problems.add(
              Problem.of(
                  module,
                  f.fileName(),
                  "changed after it was applied (applied checksum "
                      + e.getValue().checksum().substring(0, 12)
                      + "..., file checksum "
                      + f.checksum().substring(0, 12)
                      + "...); applied migrations must never be edited, add a new version"));
        }
      }
      for (MigrationFile f : byVersion.values()) {
        if (done.containsKey(f.version())) {
          continue;
        }
        if (f.version() < highestApplied) {
          problems.add(
              Problem.of(
                  module,
                  f.fileName(),
                  "was never applied, but a higher version ("
                      + highestApplied
                      + ") already was; versions must not be added below the applied ones"));
        } else {
          pending.add(f);
        }
      }
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }

    for (MigrationFile f : pending) {
      apply(c, f);
    }
  }

  private void apply(Connection c, MigrationFile f) throws SQLException {
    LOG.info("applying migration {} of module '{}'", f.fileName(), f.module());
    c.setAutoCommit(false);
    try {
      try (Statement s = c.createStatement()) {
        s.setEscapeProcessing(false);
        s.execute(f.sql());
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO fw_schema_history (module, version, description, checksum, applied_at) "
                  + "VALUES (?, ?, ?, ?, now())")) {
        ps.setString(1, f.module());
        ps.setInt(2, f.version());
        ps.setString(3, f.description());
        ps.setString(4, f.checksum());
        ps.executeUpdate();
      }
      c.commit();
    } catch (SQLException e) {
      try {
        c.rollback();
      } catch (SQLException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw new StartupException(
          List.of(
              new Problem(
                  f.module(),
                  f.fileName(),
                  null,
                  "migration failed and was rolled back: " + e.getMessage(),
                  e)));
    } finally {
      c.setAutoCommit(true);
    }
  }

  private static Map<String, Map<Integer, Applied>> readHistory(Connection c) throws SQLException {
    Map<String, Map<Integer, Applied>> out = new HashMap<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT module, version, description, checksum FROM fw_schema_history")) {
      while (rs.next()) {
        out.computeIfAbsent(rs.getString(1), m -> new HashMap<>())
            .put(rs.getInt(2), new Applied(rs.getInt(2), rs.getString(3), rs.getString(4)));
      }
    }
    return out;
  }

  private void checkDuplicates(String module, List<MigrationFile> moduleFiles) {
    Map<Integer, String> seen = new HashMap<>();
    Set<Integer> reported = new HashSet<>();
    for (MigrationFile f : moduleFiles) {
      String other = seen.putIfAbsent(f.version(), f.fileName());
      if (other != null && reported.add(f.version())) {
        problems.add(
            Problem.of(
                module,
                f.fileName(),
                "has the same version (" + f.version() + ") as " + other));
      }
    }
  }

  private List<MigrationFile> readSet(MigrationSet set) {
    List<ClasspathFiles.Entry> entries;
    try {
      entries = ClasspathFiles.list(set.loader(), set.location());
    } catch (IOException e) {
      problems.add(
          new Problem(
              set.module(),
              set.location(),
              null,
              "cannot read migration location: " + e.getMessage(),
              e));
      return List.of();
    }
    if (entries.isEmpty()) {
      problems.add(
          Problem.of(
              set.module(),
              set.location(),
              "migration location not found or empty on the classpath"));
      return List.of();
    }
    List<MigrationFile> out = new ArrayList<>();
    for (ClasspathFiles.Entry e : entries) {
      if (!e.name().endsWith(".sql")) {
        continue;
      }
      Matcher m = FILE.matcher(e.name());
      if (!m.matches()) {
        problems.add(
            Problem.of(
                set.module(), e.name(), "does not match V<version>__<description>.sql"));
        continue;
      }
      int version;
      try {
        version = Integer.parseInt(m.group(1));
      } catch (NumberFormatException ex) {
        version = 0;
      }
      if (version < 1) {
        problems.add(Problem.of(set.module(), e.name(), "version must be a positive integer"));
        continue;
      }
      out.add(
          new MigrationFile(
              set.module(),
              version,
              m.group(2).replace('_', ' '),
              e.name(),
              sha256(e.bytes()),
              new String(e.bytes(), StandardCharsets.UTF_8)));
    }
    return out;
  }

  private static void lock(Connection c) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_lock(?)")) {
      ps.setLong(1, ADVISORY_LOCK_KEY);
      ps.execute();
    }
  }

  private static void unlock(Connection c) {
    try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
      ps.setLong(1, ADVISORY_LOCK_KEY);
      ps.execute();
    } catch (SQLException e) {
      LOG.warn("could not release the migration advisory lock", e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
