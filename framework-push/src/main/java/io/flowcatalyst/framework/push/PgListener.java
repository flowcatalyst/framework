package io.flowcatalyst.framework.push;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

/**
 * A connection of its own (taken out of the pool for as long as it lives) that listens on
 * PostgreSQL notification channels. {@link #await} returns the notifications that arrived, waiting
 * at most the time given; closing it stops listening and gives the connection back.
 */
final class PgListener implements AutoCloseable {

  private final Connection connection;
  private final PGConnection postgres;

  PgListener(DataSource dataSource, List<String> channels) throws SQLException {
    Connection c = dataSource.getConnection();
    try {
      this.postgres = c.unwrap(PGConnection.class);
      try (Statement statement = c.createStatement()) {
        for (String channel : channels) {
          statement.execute("LISTEN " + channel);
        }
      }
    } catch (SQLException | RuntimeException e) {
      closeQuietly(c);
      throw e;
    }
    this.connection = c;
  }

  /**
   * Waits for notifications.
   *
   * @param millis the longest wait
   * @return the notifications that arrived, none when the wait ran out
   * @throws SQLException when the connection fails
   */
  PGNotification[] await(int millis) throws SQLException {
    PGNotification[] arrived = postgres.getNotifications(millis);
    return arrived == null ? new PGNotification[0] : arrived;
  }

  @Override
  public void close() {
    try (Statement statement = connection.createStatement()) {
      statement.execute("UNLISTEN *");
    } catch (SQLException e) {
      // the connection is broken; closing it below discards it
    }
    closeQuietly(connection);
  }

  private static void closeQuietly(Connection c) {
    try {
      c.close();
    } catch (SQLException e) {
      // nothing more to do with it
    }
  }
}
