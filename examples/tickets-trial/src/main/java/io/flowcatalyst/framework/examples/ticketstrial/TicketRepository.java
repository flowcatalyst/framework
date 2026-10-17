package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.examples.ticketstrial.TicketSql.FindHeaderRow;
import io.flowcatalyst.framework.usecase.Repository;
import io.flowcatalyst.framework.usecase.Tx;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The SQL of the ticket and its comments lives in {@code TicketSql.sql}, checked against the schema
 * when the project builds. The root row carries the version. Comments are append-only, so an update
 * writes only the positions that are not stored yet (the version guard means one writer at a time
 * reaches that count).
 */
public class TicketRepository implements Repository<TicketId, Ticket> {

  @Override
  public Optional<Ticket> find(Database db, ClientId client, TicketId id) throws SQLException {
    try (Connection c = db.dataSource().getConnection()) {
      Optional<FindHeaderRow> header = TicketSql.findHeader(c, id, client);
      if (header.isEmpty()) {
        return Optional.empty();
      }
      List<Comment> comments =
          TicketSql.commentsOf(c, id).stream()
              .map(r -> new Comment(r.commentId(), r.authorId(), r.body(), r.postedAt()))
              .toList();
      FindHeaderRow h = header.get();
      return Optional.of(
          new Ticket(
              id, client, h.version(), h.subject(), h.description(), h.priority(), h.status(), h.requesterId(),
              h.assigneeId(), h.openedAt(), h.dueAt(), h.breachedAt(), h.resolvedAt(), h.resolution(), comments));
    }
  }

  @Override
  public void insert(Tx tx, Ticket t) throws SQLException {
    Connection c = tx.connection();
    TicketSql.insertTicket(
        c, t.id(), t.client(), t.subject(), t.description(), t.priority(), t.status(), t.requesterId(),
        t.assigneeId(), t.openedAt(), t.dueAt(), t.breachedAt(), t.resolvedAt(), t.resolution());
    writeComments(c, t, 0);
  }

  @Override
  public int update(Tx tx, Ticket t) throws SQLException {
    Connection c = tx.connection();
    int rows =
        TicketSql.updateTicket(
            c, t.version() + 1, t.subject(), t.description(), t.priority(), t.status(), t.assigneeId(), t.dueAt(),
            t.breachedAt(), t.resolvedAt(), t.resolution(), t.id(), t.client(), t.version());
    if (rows == 1) {
      int stored = TicketSql.commentCount(c, t.id()).map(r -> (int) r.n()).orElse(0);
      writeComments(c, t, stored);
    }
    return rows;
  }

  @Override
  public int delete(Tx tx, Ticket t) throws SQLException {
    // The comments go with the ticket: ON DELETE CASCADE.
    return TicketSql.deleteTicket(tx.connection(), t.id(), t.client(), t.version());
  }

  private static void writeComments(Connection c, Ticket t, int stored) throws SQLException {
    List<Comment> comments = t.comments();
    for (int position = stored; position < comments.size(); position++) {
      Comment comment = comments.get(position);
      TicketSql.insertComment(c, t.id(), position, comment.commentId(), comment.authorId(), comment.body(), comment.postedAt());
    }
  }
}
