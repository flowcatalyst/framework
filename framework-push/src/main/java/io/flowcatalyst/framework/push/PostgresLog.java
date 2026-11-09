package io.flowcatalyst.framework.push;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The A1 log driver (push contract, section 4): the table {@code fw_push_log} on the default
 * database instance, ids {@code max(id) + 1} assigned by the relay in its transaction (the primary
 * key refuses a second relay), followers woken by {@code pg_notify('fw_push_log', <highest id>)}.
 */
public final class PostgresLog implements Log {

  /** A push was written (the relay listens). */
  static final String CHANNEL_OUTBOX = "fw_push_outbox";

  /** The relay logged pushes; the payload is the highest id. */
  static final String CHANNEL_LOG = "fw_push_log";

  /** End the streams of a holder; the payload is its JSON. */
  static final String CHANNEL_END = "fw_push_end";

  private static final Logger LOG = LoggerFactory.getLogger(PostgresLog.class);

  private static final String APPEND_SQL =
      "INSERT INTO fw_push_log (id, topic, type, client, data, logged_at)"
          + " SELECT r.id, r.topic, r.type, r.client, r.data, ?"
          + " FROM unnest(?::bigint[], ?::text[], ?::text[], ?::text[], ?::text[])"
          + " AS r(id, topic, type, client, data)";

  private static final String READ_AFTER_SQL =
      "SELECT id, topic, type, coalesce(client, ''), data, logged_at FROM fw_push_log"
          + " WHERE id > ? ORDER BY id LIMIT ?";

  private static final String NEWEST_SQL =
      "SELECT id, topic, type, client, data, logged_at FROM ("
          + " SELECT id, topic, type, coalesce(client, '') AS client, data, logged_at"
          + " FROM fw_push_log ORDER BY id DESC LIMIT ?) newest ORDER BY id";

  private final DataSource dataSource;

  /**
   * The A1 driver on a database.
   *
   * @param dataSource the database the push migrations ran on
   */
  public PostgresLog(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void append(Connection tx, List<OutboxRow> rows, Instant at) {
    if (rows.isEmpty()) {
      return;
    }
    try {
      long top;
      try (Statement statement = tx.createStatement();
          ResultSet rs = statement.executeQuery("SELECT coalesce(max(id), 0) FROM fw_push_log")) {
        rs.next();
        top = rs.getLong(1);
      }
      int n = rows.size();
      Object[] ids = new Object[n];
      Object[] topics = new Object[n];
      Object[] types = new Object[n];
      @Nullable Object[] clients = new Object[n];
      Object[] data = new Object[n];
      for (int i = 0; i < n; i++) {
        OutboxRow r = rows.get(i);
        ids[i] = top + i + 1;
        topics[i] = r.topic();
        types[i] = r.type();
        clients[i] = r.client().isEmpty() ? null : r.client();
        data[i] = r.data();
      }
      try (PreparedStatement statement = tx.prepareStatement(APPEND_SQL)) {
        statement.setTimestamp(1, Timestamp.from(at));
        statement.setArray(2, tx.createArrayOf("bigint", ids));
        statement.setArray(3, tx.createArrayOf("text", topics));
        statement.setArray(4, tx.createArrayOf("text", types));
        statement.setArray(5, tx.createArrayOf("text", clients));
        statement.setArray(6, tx.createArrayOf("text", data));
        statement.executeUpdate();
      }
      try (PreparedStatement statement = tx.prepareStatement("SELECT pg_notify(?, ?)")) {
        statement.setString(1, CHANNEL_LOG);
        statement.setString(2, Long.toString(top + n));
        statement.execute();
      }
    } catch (SQLException e) {
      throw new LogException("push: append to the log", e);
    }
  }

  @Override
  public List<Entry> readAfter(long after, int limit) {
    return read(READ_AFTER_SQL, after, limit);
  }

  @Override
  public List<Entry> newest(int limit) {
    return read(NEWEST_SQL, limit);
  }

  private List<Entry> read(String sql, long... parameters) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setLong(i + 1, parameters[i]);
      }
      try (ResultSet rs = statement.executeQuery()) {
        List<Entry> out = new ArrayList<>();
        while (rs.next()) {
          out.add(
              new Entry(
                  rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()));
        }
        return out;
      }
    } catch (SQLException e) {
      throw new LogException("push: read the log", e);
    }
  }

  @Override
  public long latest() {
    return scalar("SELECT coalesce(max(id), 0) FROM fw_push_log");
  }

  /** The ids have no gaps, so everything below the oldest kept was deleted. */
  @Override
  public long trimmed() {
    return scalar("SELECT coalesce(min(id) - 1, 0) FROM fw_push_log");
  }

  private long scalar(String sql) {
    try (Connection c = dataSource.getConnection();
        Statement statement = c.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new LogException("push: read the log", e);
    }
  }

  /** The newest row stays, so max(id) + 1 never starts again at 1. */
  @Override
  public long prune(Instant before) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement statement =
            c.prepareStatement(
                "DELETE FROM fw_push_log WHERE logged_at < ? AND id < (SELECT max(id) FROM fw_push_log)")) {
      statement.setTimestamp(1, Timestamp.from(before));
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new LogException("push: prune the log", e);
    }
  }

  @Override
  public void follow(Follower follower, BooleanSupplier stopping) {
    try (PgListener listener = new PgListener(dataSource, List.of(CHANNEL_LOG, CHANNEL_END))) {
      follower.grown();
      while (!stopping.getAsBoolean()) {
        for (PGNotification n : listener.await(250)) {
          switch (n.getName()) {
            case CHANNEL_LOG -> follower.grown();
            case CHANNEL_END -> {
              Holder holder = Holder.fromJson(n.getParameter());
              if (!holder.isEmpty()) {
                follower.end(holder);
              }
            }
            default -> LOG.debug("push: a notification on {} is not ours", n.getName());
          }
        }
      }
    } catch (SQLException e) {
      throw new LogException("push: follow the log", e);
    }
  }

  @Override
  public void end(Holder holder) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement statement = c.prepareStatement("SELECT pg_notify(?, ?)")) {
      statement.setString(1, CHANNEL_END);
      statement.setString(2, holder.toJson());
      statement.execute();
    } catch (SQLException e) {
      throw new LogException("push: notify the end of streams", e);
    }
  }

  @Override
  public void ping() {
    try (Connection c = dataSource.getConnection();
        Statement statement = c.createStatement()) {
      statement.execute("SELECT 1");
    } catch (SQLException e) {
      throw new LogException("push: ping the log", e);
    }
  }
}
