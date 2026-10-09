package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.core.HealthCheck;
import io.flowcatalyst.framework.database.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;

/**
 * The {@code outbox} health check (flowcatalyst contract, section 3): down when the oldest row not
 * yet delivered has waited longer than {@code flowcatalyst.outbox.stale-after}.
 *
 * <p>Delivery is the platform's outbox processor; this check is how an operator learns that the
 * processor is not running, or cannot keep up. It only reads: it never claims, updates or deletes
 * a row.
 *
 * <p>"Not yet delivered" is status {@code 0} (PENDING) or {@code 9} (IN_PROGRESS, claimed by a
 * processor). Rows with a terminal failure status are not counted: the processor will not retry
 * them, so they say nothing about whether it is running. Ages are measured with the application's
 * {@link InstantSource}, the clock the framework's outbox writer stamps {@code created_at} with.
 */
final class OutboxHealthCheck implements HealthCheck {

  /** The table the SDK and the framework's outbox module create, and the processor reads. */
  static final String TABLE = "outbox_messages";

  // Two scalar sub-queries so each uses its partial index (status = 0, status = 9).
  // LEAST ignores NULLs in PostgreSQL.
  private static final String OLDEST =
      "SELECT LEAST("
          + "(SELECT min(created_at) FROM outbox_messages WHERE status = 0), "
          + "(SELECT min(created_at) FROM outbox_messages WHERE status = 9))";

  private final Database database;
  private final Duration staleAfter;
  private final InstantSource clock;

  OutboxHealthCheck(Database database, Duration staleAfter, InstantSource clock) {
    this.database = database;
    this.staleAfter = staleAfter;
    this.clock = clock;
  }

  @Override
  public void check() throws SQLException {
    Optional<Instant> oldest = oldestUndelivered();
    if (oldest.isEmpty()) {
      return;
    }
    Duration age = Duration.between(oldest.get(), clock.instant());
    if (age.compareTo(staleAfter) > 0) {
      throw new IllegalStateException(
          "the oldest undelivered outbox row has waited "
              + age.toSeconds()
              + "s (stale after "
              + staleAfter.toSeconds()
              + "s); is the platform's outbox processor running against this database?");
    }
  }

  /**
   * The {@code created_at} of the oldest pending or in-progress row.
   *
   * @return the time, or empty when every row is delivered or terminally failed
   * @throws SQLException on a database error
   */
  Optional<Instant> oldestUndelivered() throws SQLException {
    try (Connection c = database.dataSource().getConnection();
        PreparedStatement s = c.prepareStatement(OLDEST);
        ResultSet rs = s.executeQuery()) {
      if (!rs.next()) {
        return Optional.empty();
      }
      Timestamp t = rs.getTimestamp(1);
      return t == null ? Optional.empty() : Optional.of(t.toInstant());
    }
  }
}
