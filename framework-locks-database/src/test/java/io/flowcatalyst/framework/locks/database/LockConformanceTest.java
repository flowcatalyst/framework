package io.flowcatalyst.framework.locks.database;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.database.Migrations;
import io.flowcatalyst.framework.database.PostgresFixture;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Writes and checks the shared lock statements ({@code conformance/sql/locks/}) and the history the
 * Java migration runner records for this module's set ({@code conformance/migrations/locks-database.json}),
 * which the Rust implementation reads.
 */
class LockConformanceTest {

  @Test
  void statementsAreTheSharedFiles() {
    Conformance.check("sql/locks/acquire.sql", DatabaseLockDriver.ACQUIRE);
    Conformance.check("sql/locks/refresh.sql", DatabaseLockDriver.REFRESH);
    Conformance.check("sql/locks/release.sql", DatabaseLockDriver.RELEASE);
  }

  @Test
  void migrationHistory() throws SQLException {
    try (PostgresFixture pg = PostgresFixture.start()) {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(pg.jdbcUrl());
      config.setUsername(pg.username());
      config.setPassword(pg.password());
      try (HikariDataSource ds = new HikariDataSource(config)) {
        Migrations.apply(
            "main",
            ds,
            List.of(
                new MigrationSet(
                    LocksDatabaseModule.ID, LockConformanceTest.class.getClassLoader(), "db/migration/locks")));
        Conformance.check("migrations/locks-database.json", history(ds, LocksDatabaseModule.ID));
      }
    }
  }

  /** The history rows of one module as JSON, without applied_at. */
  static String history(HikariDataSource ds, String module) throws SQLException {
    StringBuilder out = new StringBuilder();
    out.append("{\n  \"description\" : \"The fw_schema_history rows the Java migration runner writes for the set of module ")
        .append(module)
        .append(" (applied_at left out). A Rust run of the same set writes the same rows, and the Rust runner accepts a database holding them.\",\n");
    out.append("  \"module\" : \"").append(module).append("\",\n  \"rows\" : [");
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT version, description, checksum FROM fw_schema_history WHERE module = '"
                    + module
                    + "' ORDER BY version")) {
      boolean first = true;
      while (rs.next()) {
        out.append(first ? " " : ", ");
        first = false;
        out.append("{\n    \"version\" : ")
            .append(rs.getInt(1))
            .append(",\n    \"description\" : \"")
            .append(rs.getString(2))
            .append("\",\n    \"checksum\" : \"")
            .append(rs.getString(3))
            .append("\"\n  }");
      }
    }
    return out.append(" ]\n}\n").toString();
  }
}
