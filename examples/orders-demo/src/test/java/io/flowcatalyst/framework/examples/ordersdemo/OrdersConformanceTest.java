package io.flowcatalyst.framework.examples.ordersdemo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.database.MigrationSet;
import io.flowcatalyst.framework.database.Migrations;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Writes and checks {@code conformance/migrations/orders.json}: the history the Java runner records
 * for this demo's migration set, which the Rust orders-demo uses to build "a database first
 * migrated by the Java demo".
 */
class OrdersConformanceTest {

  @Test
  void migrationHistory() throws Exception {
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
                    OrdersModule.ID, OrdersConformanceTest.class.getClassLoader(), "db/migration/orders")));
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"description\" : \"The fw_schema_history rows the Java migration runner writes for the set of module orders (applied_at left out). A Rust run of the same set writes the same rows, and the Rust runner accepts a database holding them.\",\n");
        out.append("  \"module\" : \"orders\",\n  \"rows\" : [");
        try (Connection c = ds.getConnection();
            Statement s = c.createStatement();
            ResultSet rs =
                s.executeQuery(
                    "SELECT version, description, checksum FROM fw_schema_history WHERE module = 'orders' ORDER BY version")) {
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
        Conformance.check("migrations/orders.json", out.append(" ]\n}\n").toString());
      }
    }
  }
}
