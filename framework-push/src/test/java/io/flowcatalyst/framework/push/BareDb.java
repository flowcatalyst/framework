package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.metrics.Metrics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import org.postgresql.ds.PGSimpleDataSource;

/** The push tables on a database, and a relay and log on it without an application around them. */
final class BareDb {

  final PGSimpleDataSource dataSource = new PGSimpleDataSource();
  final PostgresLog log;
  final PushMetrics metrics = new PushMetrics(Metrics.memory(100, () -> 0));

  BareDb(PostgresFixture pg) throws IOException {
    dataSource.setUrl(pg.jdbcUrl());
    dataSource.setUser(pg.username());
    dataSource.setPassword(pg.password());
    try (InputStream sql = BareDb.class.getResourceAsStream("/db/migration/push/V0001__create_push_tables.sql")) {
      pg.execute(new String(sql.readAllBytes(), StandardCharsets.UTF_8));
    }
    this.log = new PostgresLog(dataSource);
  }

  Relay relay(int batch) {
    return new Relay(dataSource, log, () -> TestNode.BEGIN, metrics, batch, 1000);
  }

  /** Writes a push of client acme to the outbox in the transaction. */
  static void insertPush(Connection tx, String note) throws SQLException {
    try (Statement s = tx.createStatement()) {
      s.execute(
          "INSERT INTO fw_push_outbox (topic, type, client, data, created_at) VALUES ('test:order:1', 'order.updated', 'acme', '{\"note\":\""
              + note
              + "\"}', '"
              + Instant.parse("2026-11-08T12:00:00Z")
              + "')");
    }
  }

  long count(String table) throws SQLException {
    try (Connection c = dataSource.getConnection();
        Statement s = c.createStatement();
        var rs = s.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
