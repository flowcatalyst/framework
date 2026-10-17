package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads for screens, lists and the background jobs: plain typed queries, not aggregates
 * (write-path contract, section 9). Every one but {@link #overdue} and {@link #totals} is scoped to
 * a client.
 */
public final class TicketQueries {

  /**
   * One row of a ticket list.
   *
   * @param id the ticket id
   * @param subject its summary
   * @param priority how urgent
   * @param status where it is
   * @param requesterId who opened it
   * @param assigneeId who has it, if anyone
   * @param openedAt when it was opened
   * @param dueAt its SLA deadline
   * @param breachedAt when the sweep flagged it, if it did
   * @param version its version
   */
  public record Summary(
      String id,
      String subject,
      Priority priority,
      TicketStatus status,
      String requesterId,
      @Nullable String assigneeId,
      Instant openedAt,
      Instant dueAt,
      @Nullable Instant breachedAt,
      long version) {}

  /**
   * One page of tickets.
   *
   * @param items the tickets, newest first
   * @param nextCursor the id to pass as {@code after} for the next page; null on the last page
   */
  public record Page(List<Summary> items, @Nullable String nextCursor) {}

  /**
   * A ticket past its deadline, from any client.
   *
   * @param id the ticket
   * @param client the client it belongs to
   * @param dueAt the deadline
   */
  public record Overdue(TicketId id, ClientId client, Instant dueAt) {}

  /**
   * Tickets of one status.
   *
   * @param status the status
   * @param count how many tickets
   * @param flagged how many of them the sweep has flagged as past their deadline
   */
  public record StatusCount(TicketStatus status, long count, long flagged) {}

  private TicketQueries() {}

  /**
   * A page of a client's tickets, newest first.
   *
   * @param db the database
   * @param client the client; only its tickets are listed
   * @param status only this status, or null for any
   * @param assignee only tickets of this assignee, or null for any
   * @param requester only tickets of this requester, or null for any
   * @param after the previous page's {@code nextCursor}, or null for the first page
   * @param limit the page size, 1 to 200
   * @return the page
   * @throws SQLException on a database error
   */
  public static Page list(
      Database db,
      ClientId client,
      @Nullable TicketStatus status,
      @Nullable String assignee,
      @Nullable String requester,
      @Nullable String after,
      int limit)
      throws SQLException {
    int size = Math.max(1, Math.min(limit, 200));
    try (Connection c = db.dataSource().getConnection()) {
      List<Summary> rows =
          TicketSql.list(c, client, status == null ? null : status.name(), assignee, requester, after, size + 1L).stream()
              .map(
                  r ->
                      new Summary(
                          r.id().value(),
                          r.subject(),
                          r.priority(),
                          r.status(),
                          r.requesterId(),
                          r.assigneeId(),
                          r.openedAt(),
                          r.dueAt(),
                          r.breachedAt(),
                          r.version()))
              .toList();
      if (rows.size() <= size) {
        return new Page(rows, null);
      }
      List<Summary> page = rows.subList(0, size);
      return new Page(page, page.getLast().id());
    }
  }

  /**
   * Active tickets of any client whose deadline has passed and that were not flagged yet.
   *
   * @param db the database
   * @param now the time to compare with
   * @param limit the most to return
   * @return the tickets, oldest deadline first
   * @throws SQLException on a database error
   */
  public static List<Overdue> overdue(Database db, Instant now, int limit) throws SQLException {
    try (Connection c = db.dataSource().getConnection()) {
      return TicketSql.overdue(c, now, limit).stream()
          .map(r -> new Overdue(r.id(), new ClientId(r.clientId()), r.dueAt()))
          .toList();
    }
  }

  /**
   * A client's tickets counted by status.
   *
   * @param db the database
   * @param client the client
   * @return one entry per status that has tickets
   * @throws SQLException on a database error
   */
  public static List<StatusCount> counts(Database db, ClientId client) throws SQLException {
    try (Connection c = db.dataSource().getConnection()) {
      return TicketSql.counts(c, client).stream()
          .map(r -> new StatusCount(r.status(), r.n(), r.overdue()))
          .toList();
    }
  }

  /**
   * Open and overdue tickets across every client.
   *
   * @param db the database
   * @return {open, overdue}
   * @throws SQLException on a database error
   */
  public static long[] totals(Database db) throws SQLException {
    try (Connection c = db.dataSource().getConnection()) {
      return TicketSql.totals(c).map(r -> new long[] {r.open(), r.overdue()}).orElse(new long[] {0, 0});
    }
  }
}
