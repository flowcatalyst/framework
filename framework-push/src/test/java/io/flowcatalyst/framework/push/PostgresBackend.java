package io.flowcatalyst.framework.push;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;

/** The A1 backend: the table {@code fw_push_log}. */
final class PostgresBackend implements LogBackend {

  @Override
  public Log bare(DataSource database) {
    return new PostgresLog(database);
  }

  @Override
  public boolean contiguousIds() {
    return true;
  }

  @Override
  public String platformClientStored() {
    return "<null>";
  }

  @Override
  public List<String> storedClients(TestNode node) {
    return node.query("SELECT coalesce(client, '<null>') FROM fw_push_log ORDER BY id");
  }

  @Override
  public void deleteUpTo(TestNode node, long id) {
    node.execute("DELETE FROM fw_push_log WHERE id <= " + id);
  }

  @Override
  public void load(TestNode node, List<Entry> entries, long trimmed) {
    for (Entry e : entries) {
      try (Connection conn = node.dataSource.getConnection();
          PreparedStatement ps = conn.prepareStatement("INSERT INTO fw_push_log (id, topic, type, client, data, logged_at) VALUES (?, ?, ?, ?, ?, ?)")) {
        ps.setLong(1, e.id());
        ps.setString(2, e.topic());
        ps.setString(3, e.type());
        ps.setString(4, e.client().isEmpty() ? null : e.client());
        ps.setString(5, e.data());
        ps.setTimestamp(6, Timestamp.from(e.loggedAt()));
        ps.executeUpdate();
      } catch (SQLException ex) {
        Assertions.fail("load the log", ex);
      }
    }
  }
}
