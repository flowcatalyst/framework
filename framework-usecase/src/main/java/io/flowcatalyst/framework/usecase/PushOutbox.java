package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.ClientId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Writes a push to {@code fw_push_outbox} (push contract, section 2): the row and a
 * {@code pg_notify('fw_push_outbox', '')}, in the caller's transaction, so both exist only if it
 * commits. The runner uses it for {@link Plan#andPush}; {@code Pushes.publish} uses it outside a use
 * case. The platform client is written as NULL (section 5). The table is the push module's
 * ({@code framework-push}).
 */
public final class PushOutbox {

  /** The notification channel the relay listens on. */
  public static final String CHANNEL = "fw_push_outbox";

  private static final String INSERT =
      "WITH woken AS (SELECT pg_notify('fw_push_outbox', ''))"
          + " INSERT INTO fw_push_outbox (topic, type, client, data, created_at)"
          + " SELECT ?::varchar, ?::varchar, ?::varchar, ?::text, ?::timestamptz FROM woken";

  private PushOutbox() {}

  /**
   * Writes one checked push in the caller's transaction.
   *
   * @param connection the transaction's connection
   * @param push the push
   * @param client the push's client (the context's); the platform client is stored as NULL
   * @param at when it was written
   * @throws SQLException on a database error
   */
  public static void insert(Connection connection, Push push, ClientId client, Instant at)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
      statement.setString(1, push.topic());
      statement.setString(2, push.type());
      statement.setString(3, client.equals(ClientId.PLATFORM) ? null : client.value());
      statement.setString(4, push.data());
      statement.setTimestamp(5, Timestamp.from(at));
      statement.executeUpdate();
    }
  }
}
