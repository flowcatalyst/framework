// Generated from src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql. Do not edit.
package io.flowcatalyst.framework.examples.ticketstrial;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Typed queries of src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql. Generated; do not edit. */
public final class TicketSql {

  private TicketSql() {}

  /** One row of {@link #findHeader}. */
  public record FindHeaderRow(
      long version,
      String subject,
      String description,
      io.flowcatalyst.framework.examples.ticketstrial.Priority priority,
      io.flowcatalyst.framework.examples.ticketstrial.TicketStatus status,
      String requesterId,
      @Nullable String assigneeId,
      Instant openedAt,
      Instant dueAt,
      @Nullable Instant breachedAt,
      @Nullable Instant resolvedAt,
      @Nullable String resolution) {}

  /** The text of {@link #findHeader}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:10
  public static final String FIND_HEADER_SQL =
      """
      SELECT version, subject, description, priority, status, requester_id, assignee_id,
             opened_at, due_at, breached_at, resolved_at, resolution
        FROM tickets
       WHERE id = ? AND client_id = ?""";

  /**
   * The ticket's root row, for its client only.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:4.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   */
  public static Optional<FindHeaderRow> findHeader(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.TicketId id,
      io.flowcatalyst.framework.context.ClientId clientId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(FIND_HEADER_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        FindHeaderRow row = readFindHeaderRow(rs);
        if (rs.next()) {
          throw new IllegalStateException(
              "findHeader: expected at most one row, got more");
        }
        return Optional.of(row);
      }
    }
  }

  private static FindHeaderRow readFindHeaderRow(ResultSet rs) throws SQLException {
    long v0 = rs.getLong(1);
    String v1 = rs.getString(2);
    String v2 = rs.getString(3);
    io.flowcatalyst.framework.examples.ticketstrial.Priority v3 = io.flowcatalyst.framework.examples.ticketstrial.Priority.valueOf(rs.getString(4));
    io.flowcatalyst.framework.examples.ticketstrial.TicketStatus v4 = io.flowcatalyst.framework.examples.ticketstrial.TicketStatus.valueOf(rs.getString(5));
    String v5 = rs.getString(6);
    String r6 = rs.getString(7);
    @Nullable String v6 = r6 == null ? null : r6;
    Instant v7 = rs.getObject(8, OffsetDateTime.class).toInstant();
    Instant v8 = rs.getObject(9, OffsetDateTime.class).toInstant();
    OffsetDateTime r9 = rs.getObject(10, OffsetDateTime.class);
    @Nullable Instant v9 = r9 == null ? null : r9.toInstant();
    OffsetDateTime r10 = rs.getObject(11, OffsetDateTime.class);
    @Nullable Instant v10 = r10 == null ? null : r10.toInstant();
    String r11 = rs.getString(12);
    @Nullable String v11 = r11 == null ? null : r11;
    return new FindHeaderRow(v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11);
  }

  /** One row of {@link #commentsOf}. */
  public record CommentsOfRow(
      String commentId,
      String authorId,
      String body,
      Instant postedAt) {}

  /** The text of {@link #commentsOf}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:18
  public static final String COMMENTS_OF_SQL =
      """
      SELECT comment_id, author_id, body, posted_at
        FROM ticket_comments
       WHERE ticket_id = ?
       ORDER BY position""";

  /**
   * The ticket's comments in the order they were posted.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:15.
   *
   * @param connection the connection, for example a transaction's
   * @param ticketId :ticket_id
   * @return the rows, in the order the database returned them
   * @throws SQLException on a database error
   */
  public static List<CommentsOfRow> commentsOf(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.TicketId ticketId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(COMMENTS_OF_SQL)) {
      ps.setString(1, ticketId.value());
      try (ResultSet rs = ps.executeQuery()) {
        List<CommentsOfRow> list = new ArrayList<>();
        while (rs.next()) {
          list.add(readCommentsOfRow(rs));
        }
        return list;
      }
    }
  }

  private static CommentsOfRow readCommentsOfRow(ResultSet rs) throws SQLException {
    String v0 = rs.getString(1);
    String v1 = rs.getString(2);
    String v2 = rs.getString(3);
    Instant v3 = rs.getObject(4, OffsetDateTime.class).toInstant();
    return new CommentsOfRow(v0, v1, v2, v3);
  }

  /** One row of {@link #commentCount}. */
  public record CommentCountRow(
      long n) {}

  /** The text of {@link #commentCount}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:27
  public static final String COMMENT_COUNT_SQL =
      """
      SELECT count(*) AS n FROM ticket_comments WHERE ticket_id = ?""";

  /**
   * How many comments are stored, which is the next position to write.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:23.
   *
   * @param connection the connection, for example a transaction's
   * @param ticketId :ticket_id
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   */
  public static Optional<CommentCountRow> commentCount(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.TicketId ticketId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(COMMENT_COUNT_SQL)) {
      ps.setString(1, ticketId.value());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        CommentCountRow row = readCommentCountRow(rs);
        if (rs.next()) {
          throw new IllegalStateException(
              "commentCount: expected at most one row, got more");
        }
        return Optional.of(row);
      }
    }
  }

  private static CommentCountRow readCommentCountRow(ResultSet rs) throws SQLException {
    long r0 = rs.getLong(1);
    if (rs.wasNull()) {
      throw nullRead("commentCount", "n");
    }
    long v0 = r0;
    return new CommentCountRow(v0);
  }

  /** The text of {@link #insertTicket}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:38
  public static final String INSERT_TICKET_SQL =
      """
      INSERT INTO tickets (id, client_id, version, subject, description, priority, status, requester_id,
                           assignee_id, opened_at, due_at, breached_at, resolved_at, resolution)
      VALUES (?, ?, 1, ?, ?, ?, ?, ?,
              ?, ?, ?, ?, ?, ?)""";

  /**
   * The {@code insertTicket} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:29.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @param subject :subject
   * @param description :description
   * @param priority :priority
   * @param status :status
   * @param requesterId :requester_id
   * @param assigneeId :assignee_id
   * @param openedAt :opened_at
   * @param dueAt :due_at
   * @param breachedAt :breached_at
   * @param resolvedAt :resolved_at
   * @param resolution :resolution
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int insertTicket(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.TicketId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      String subject,
      String description,
      io.flowcatalyst.framework.examples.ticketstrial.Priority priority,
      io.flowcatalyst.framework.examples.ticketstrial.TicketStatus status,
      String requesterId,
      @Nullable String assigneeId,
      Instant openedAt,
      Instant dueAt,
      @Nullable Instant breachedAt,
      @Nullable Instant resolvedAt,
      @Nullable String resolution)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_TICKET_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      ps.setString(3, subject);
      ps.setString(4, description);
      ps.setString(5, priority.name());
      ps.setString(6, status.name());
      ps.setString(7, requesterId);
      if (assigneeId == null) {
        ps.setNull(8, Types.VARCHAR);
      } else {
        ps.setString(8, assigneeId);
      }
      ps.setObject(9, OffsetDateTime.ofInstant(openedAt, ZoneOffset.UTC));
      ps.setObject(10, OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC));
      if (breachedAt == null) {
        ps.setNull(11, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(11, OffsetDateTime.ofInstant(breachedAt, ZoneOffset.UTC));
      }
      if (resolvedAt == null) {
        ps.setNull(12, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(12, OffsetDateTime.ofInstant(resolvedAt, ZoneOffset.UTC));
      }
      if (resolution == null) {
        ps.setNull(13, Types.VARCHAR);
      } else {
        ps.setString(13, resolution);
      }
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #updateTicket}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:53
  public static final String UPDATE_TICKET_SQL =
      """
      UPDATE tickets
         SET version = ?, subject = ?, description = ?, priority = ?,
             status = ?, assignee_id = ?, due_at = ?, breached_at = ?,
             resolved_at = ?, resolution = ?
       WHERE id = ? AND client_id = ? AND version = ?""";

  /**
   * The optimistic-locking write: the row count says whether the version still matched.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:43.
   *
   * @param connection the connection, for example a transaction's
   * @param newVersion :new_version
   * @param subject :subject
   * @param description :description
   * @param priority :priority
   * @param status :status
   * @param assigneeId :assignee_id
   * @param dueAt :due_at
   * @param breachedAt :breached_at
   * @param resolvedAt :resolved_at
   * @param resolution :resolution
   * @param id :id
   * @param clientId :client_id
   * @param version :version
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int updateTicket(
      Connection connection,
      long newVersion,
      String subject,
      String description,
      io.flowcatalyst.framework.examples.ticketstrial.Priority priority,
      io.flowcatalyst.framework.examples.ticketstrial.TicketStatus status,
      @Nullable String assigneeId,
      Instant dueAt,
      @Nullable Instant breachedAt,
      @Nullable Instant resolvedAt,
      @Nullable String resolution,
      io.flowcatalyst.framework.examples.ticketstrial.TicketId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      long version)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(UPDATE_TICKET_SQL)) {
      ps.setLong(1, newVersion);
      ps.setString(2, subject);
      ps.setString(3, description);
      ps.setString(4, priority.name());
      ps.setString(5, status.name());
      if (assigneeId == null) {
        ps.setNull(6, Types.VARCHAR);
      } else {
        ps.setString(6, assigneeId);
      }
      ps.setObject(7, OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC));
      if (breachedAt == null) {
        ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(8, OffsetDateTime.ofInstant(breachedAt, ZoneOffset.UTC));
      }
      if (resolvedAt == null) {
        ps.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        ps.setObject(9, OffsetDateTime.ofInstant(resolvedAt, ZoneOffset.UTC));
      }
      if (resolution == null) {
        ps.setNull(10, Types.VARCHAR);
      } else {
        ps.setString(10, resolution);
      }
      ps.setString(11, id.value());
      ps.setString(12, clientId.value());
      ps.setLong(13, version);
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #deleteTicket}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:62
  public static final String DELETE_TICKET_SQL =
      """
      DELETE FROM tickets
       WHERE id = ? AND client_id = ? AND version = ?""";

  /**
   * The {@code deleteTicket} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:59.
   *
   * @param connection the connection, for example a transaction's
   * @param id :id
   * @param clientId :client_id
   * @param version :version
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int deleteTicket(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.TicketId id,
      io.flowcatalyst.framework.context.ClientId clientId,
      long version)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(DELETE_TICKET_SQL)) {
      ps.setString(1, id.value());
      ps.setString(2, clientId.value());
      ps.setLong(3, version);
      return ps.executeUpdate();
    }
  }

  /** The text of {@link #insertComment}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:67
  public static final String INSERT_COMMENT_SQL =
      """
      INSERT INTO ticket_comments (ticket_id, position, comment_id, author_id, body, posted_at)
      VALUES (?, ?, ?, ?, ?, ?)""";

  /**
   * The {@code insertComment} query.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:65.
   *
   * @param connection the connection, for example a transaction's
   * @param ticketId :ticket_id
   * @param position :position
   * @param commentId :comment_id
   * @param authorId :author_id
   * @param body :body
   * @param postedAt :posted_at
   * @return the number of rows affected
   * @throws SQLException on a database error
   */
  public static int insertComment(
      Connection connection,
      io.flowcatalyst.framework.examples.ticketstrial.TicketId ticketId,
      int position,
      String commentId,
      String authorId,
      String body,
      Instant postedAt)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(INSERT_COMMENT_SQL)) {
      ps.setString(1, ticketId.value());
      ps.setInt(2, position);
      ps.setString(3, commentId);
      ps.setString(4, authorId);
      ps.setString(5, body);
      ps.setObject(6, OffsetDateTime.ofInstant(postedAt, ZoneOffset.UTC));
      return ps.executeUpdate();
    }
  }

  /** One row of {@link #list}. */
  public record ListRow(
      io.flowcatalyst.framework.examples.ticketstrial.TicketId id,
      String subject,
      io.flowcatalyst.framework.examples.ticketstrial.Priority priority,
      io.flowcatalyst.framework.examples.ticketstrial.TicketStatus status,
      String requesterId,
      @Nullable String assigneeId,
      Instant openedAt,
      Instant dueAt,
      @Nullable Instant breachedAt,
      long version) {}

  /** The text of {@link #list}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:82
  public static final String LIST_SQL =
      """
      SELECT id, subject, priority, status, requester_id, assignee_id, opened_at, due_at, breached_at, version
        FROM tickets
       WHERE client_id = ?
         AND (?::text IS NULL OR status = ?::text)
         AND (?::text IS NULL OR assignee_id = ?::text)
         AND (?::text IS NULL OR requester_id = ?::text)
         AND (?::text IS NULL OR id < ?::text)
       ORDER BY id DESC
       LIMIT ?""";

  /**
   * A client's tickets, newest first, one page. Every filter is optional (null means any); the
   * cursor is the id of the last ticket of the previous page. Asking for one more than the page
   * tells the caller whether there is a next page.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:70.
   *
   * @param connection the connection, for example a transaction's
   * @param clientId :client_id
   * @param status :status
   * @param assigneeId :assignee_id
   * @param requesterId :requester_id
   * @param after :after
   * @param pageSize :page_size
   * @return the rows, in the order the database returned them
   * @throws SQLException on a database error
   */
  public static List<ListRow> list(
      Connection connection,
      io.flowcatalyst.framework.context.ClientId clientId,
      @Nullable String status,
      @Nullable String assigneeId,
      @Nullable String requesterId,
      @Nullable String after,
      long pageSize)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(LIST_SQL)) {
      ps.setString(1, clientId.value());
      if (status == null) {
        ps.setNull(2, Types.VARCHAR);
      } else {
        ps.setString(2, status);
      }
      if (status == null) {
        ps.setNull(3, Types.VARCHAR);
      } else {
        ps.setString(3, status);
      }
      if (assigneeId == null) {
        ps.setNull(4, Types.VARCHAR);
      } else {
        ps.setString(4, assigneeId);
      }
      if (assigneeId == null) {
        ps.setNull(5, Types.VARCHAR);
      } else {
        ps.setString(5, assigneeId);
      }
      if (requesterId == null) {
        ps.setNull(6, Types.VARCHAR);
      } else {
        ps.setString(6, requesterId);
      }
      if (requesterId == null) {
        ps.setNull(7, Types.VARCHAR);
      } else {
        ps.setString(7, requesterId);
      }
      if (after == null) {
        ps.setNull(8, Types.VARCHAR);
      } else {
        ps.setString(8, after);
      }
      if (after == null) {
        ps.setNull(9, Types.VARCHAR);
      } else {
        ps.setString(9, after);
      }
      ps.setLong(10, pageSize);
      try (ResultSet rs = ps.executeQuery()) {
        List<ListRow> list = new ArrayList<>();
        while (rs.next()) {
          list.add(readListRow(rs));
        }
        return list;
      }
    }
  }

  private static ListRow readListRow(ResultSet rs) throws SQLException {
    String r0 = rs.getString(1);
    if (r0 == null) {
      throw nullRead("list", "id");
    }
    io.flowcatalyst.framework.examples.ticketstrial.TicketId v0 = new io.flowcatalyst.framework.examples.ticketstrial.TicketId(r0);
    String v1 = rs.getString(2);
    io.flowcatalyst.framework.examples.ticketstrial.Priority v2 = io.flowcatalyst.framework.examples.ticketstrial.Priority.valueOf(rs.getString(3));
    io.flowcatalyst.framework.examples.ticketstrial.TicketStatus v3 = io.flowcatalyst.framework.examples.ticketstrial.TicketStatus.valueOf(rs.getString(4));
    String v4 = rs.getString(5);
    String r5 = rs.getString(6);
    @Nullable String v5 = r5 == null ? null : r5;
    Instant v6 = rs.getObject(7, OffsetDateTime.class).toInstant();
    Instant v7 = rs.getObject(8, OffsetDateTime.class).toInstant();
    OffsetDateTime r8 = rs.getObject(9, OffsetDateTime.class);
    @Nullable Instant v8 = r8 == null ? null : r8.toInstant();
    long v9 = rs.getLong(10);
    return new ListRow(v0, v1, v2, v3, v4, v5, v6, v7, v8, v9);
  }

  /** One row of {@link #overdue}. */
  public record OverdueRow(
      io.flowcatalyst.framework.examples.ticketstrial.TicketId id,
      String clientId,
      Instant dueAt) {}

  /** The text of {@link #overdue}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:97
  public static final String OVERDUE_SQL =
      """
      SELECT id, client_id, due_at
        FROM tickets
       WHERE breached_at IS NULL
         AND status IN ('OPEN', 'IN_PROGRESS')
         AND due_at < ?
       ORDER BY due_at
       LIMIT ?""";

  /**
   * Every client's tickets that ran out of time and were not flagged yet: the one query in this
   * application that is not scoped to a client, for the SLA sweep (which then acts for each
   * ticket's own client). Oldest deadline first.
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:92.
   *
   * @param connection the connection, for example a transaction's
   * @param now :now
   * @param pageSize :page_size
   * @return the rows, in the order the database returned them
   * @throws SQLException on a database error
   */
  public static List<OverdueRow> overdue(
      Connection connection,
      Instant now,
      long pageSize)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(OVERDUE_SQL)) {
      ps.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      ps.setLong(2, pageSize);
      try (ResultSet rs = ps.executeQuery()) {
        List<OverdueRow> list = new ArrayList<>();
        while (rs.next()) {
          list.add(readOverdueRow(rs));
        }
        return list;
      }
    }
  }

  private static OverdueRow readOverdueRow(ResultSet rs) throws SQLException {
    String r0 = rs.getString(1);
    if (r0 == null) {
      throw nullRead("overdue", "id");
    }
    io.flowcatalyst.framework.examples.ticketstrial.TicketId v0 = new io.flowcatalyst.framework.examples.ticketstrial.TicketId(r0);
    String v1 = rs.getString(2);
    Instant v2 = rs.getObject(3, OffsetDateTime.class).toInstant();
    return new OverdueRow(v0, v1, v2);
  }

  /** One row of {@link #counts}. */
  public record CountsRow(
      io.flowcatalyst.framework.examples.ticketstrial.TicketStatus status,
      long n,
      long overdue) {}

  /** The text of {@link #counts}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:111
  public static final String COUNTS_SQL =
      """
      SELECT status, count(*) AS n,
             count(*) FILTER (WHERE breached_at IS NOT NULL) AS overdue
        FROM tickets
       WHERE client_id = ?
       GROUP BY status
       ORDER BY status""";

  /**
   * Open work per status for one client (the admin overview).
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:105.
   *
   * @param connection the connection, for example a transaction's
   * @param clientId :client_id
   * @return the rows, in the order the database returned them
   * @throws SQLException on a database error
   */
  public static List<CountsRow> counts(
      Connection connection,
      io.flowcatalyst.framework.context.ClientId clientId)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(COUNTS_SQL)) {
      ps.setString(1, clientId.value());
      try (ResultSet rs = ps.executeQuery()) {
        List<CountsRow> list = new ArrayList<>();
        while (rs.next()) {
          list.add(readCountsRow(rs));
        }
        return list;
      }
    }
  }

  private static CountsRow readCountsRow(ResultSet rs) throws SQLException {
    String r0 = rs.getString(1);
    if (r0 == null) {
      throw nullRead("counts", "status");
    }
    io.flowcatalyst.framework.examples.ticketstrial.TicketStatus v0 = io.flowcatalyst.framework.examples.ticketstrial.TicketStatus.valueOf(r0);
    long r1 = rs.getLong(2);
    if (rs.wasNull()) {
      throw nullRead("counts", "n");
    }
    long v1 = r1;
    long r2 = rs.getLong(3);
    if (rs.wasNull()) {
      throw nullRead("counts", "overdue");
    }
    long v2 = r2;
    return new CountsRow(v0, v1, v2);
  }

  /** One row of {@link #totals}. */
  public record TotalsRow(
      long open,
      long overdue) {}

  /** The text of {@link #totals}, as executed. */
  // src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:122
  public static final String TOTALS_SQL =
      """
      SELECT count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')) AS open,
             count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS') AND breached_at IS NOT NULL) AS overdue
        FROM tickets""";

  /**
   * Open and overdue tickets across every client (the metrics loop).
   *
   * <p>From src/main/sql/io/flowcatalyst/framework/examples/ticketstrial/TicketSql.sql:118.
   *
   * @param connection the connection, for example a transaction's
   * @return the row, or empty when there is none
   * @throws SQLException on a database error
   */
  public static Optional<TotalsRow> totals(
      Connection connection)
      throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(TOTALS_SQL)) {
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        TotalsRow row = readTotalsRow(rs);
        if (rs.next()) {
          throw new IllegalStateException(
              "totals: expected at most one row, got more");
        }
        return Optional.of(row);
      }
    }
  }

  private static TotalsRow readTotalsRow(ResultSet rs) throws SQLException {
    long r0 = rs.getLong(1);
    if (rs.wasNull()) {
      throw nullRead("totals", "open");
    }
    long v0 = r0;
    long r1 = rs.getLong(2);
    if (rs.wasNull()) {
      throw nullRead("totals", "overdue");
    }
    long v1 = r1;
    return new TotalsRow(v0, v1);
  }

  private static IllegalStateException nullRead(String query, String column) {
    return new IllegalStateException(
        "query " + query + ": column " + column
            + " was NULL but is declared non-null with '!'");
  }
}
