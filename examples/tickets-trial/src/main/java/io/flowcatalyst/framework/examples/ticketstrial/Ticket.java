package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.usecase.Aggregate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The ticket aggregate. Its comments belong to it: they are saved with it and covered by its
 * version. Methods that change it return a new value and write nothing.
 *
 * @param id the ticket id
 * @param client the client that owns it
 * @param version {@code 0} until stored
 * @param subject a one-line summary
 * @param description what is wrong
 * @param priority how urgent it is
 * @param status where it is in its life
 * @param requesterId the principal who opened it
 * @param assigneeId the principal working on it, if any
 * @param openedAt when it was opened
 * @param dueAt the SLA deadline, fixed when the ticket was opened
 * @param breachedAt when the SLA sweep noticed the deadline had passed, if it did
 * @param resolvedAt when it was last resolved, if it is
 * @param resolution how it was resolved, if it is
 * @param comments the comments, in the order they were posted
 */
public record Ticket(
    TicketId id,
    ClientId client,
    long version,
    String subject,
    String description,
    Priority priority,
    TicketStatus status,
    String requesterId,
    @Nullable String assigneeId,
    Instant openedAt,
    Instant dueAt,
    @Nullable Instant breachedAt,
    @Nullable Instant resolvedAt,
    @Nullable String resolution,
    List<Comment> comments)
    implements Aggregate {

  /** Copies the comments. */
  public Ticket {
    comments = List.copyOf(comments);
  }

  /**
   * A new open ticket without an assignee.
   *
   * @param id its id
   * @param client its client
   * @param subject the summary
   * @param description what is wrong
   * @param priority how urgent
   * @param requesterId who opened it
   * @param now when
   * @param dueAt the SLA deadline
   * @return the ticket, at version {@code 0}
   */
  public static Ticket open(
      TicketId id,
      ClientId client,
      String subject,
      String description,
      Priority priority,
      String requesterId,
      Instant now,
      Instant dueAt) {
    return new Ticket(
        id, client, 0, subject, description, priority, TicketStatus.OPEN, requesterId, null, now, dueAt, null, null,
        null, List.of());
  }

  /**
   * The ticket with another assignee.
   *
   * @param assignee the principal to work on it
   * @return the new ticket
   */
  public Ticket assignedTo(String assignee) {
    return new Ticket(
        id, client, version, subject, description, priority, status, requesterId, assignee, openedAt, dueAt,
        breachedAt, resolvedAt, resolution, comments);
  }

  /**
   * The ticket after an action. The caller has checked that the status allows it.
   *
   * @param next the status the action leads to
   * @param action the action
   * @param note the resolution for {@link TicketAction#RESOLVE}; ignored by the others
   * @param now when
   * @return the new ticket
   */
  public Ticket moved(TicketStatus next, TicketAction action, @Nullable String note, Instant now) {
    Instant newResolvedAt = resolvedAt;
    String newResolution = resolution;
    if (action == TicketAction.RESOLVE) {
      newResolvedAt = now;
      newResolution = note;
    } else if (action == TicketAction.REOPEN) {
      newResolvedAt = null;
      newResolution = null;
    }
    return new Ticket(
        id, client, version, subject, description, priority, next, requesterId, assigneeId, openedAt, dueAt,
        breachedAt, newResolvedAt, newResolution, comments);
  }

  /**
   * The ticket with one more comment.
   *
   * @param comment the comment
   * @return the new ticket
   */
  public Ticket withComment(Comment comment) {
    List<Comment> more = new ArrayList<>(comments);
    more.add(comment);
    return new Ticket(
        id, client, version, subject, description, priority, status, requesterId, assigneeId, openedAt, dueAt,
        breachedAt, resolvedAt, resolution, more);
  }

  /**
   * The ticket, marked as past its SLA deadline.
   *
   * @param now when it was noticed
   * @return the new ticket
   */
  public Ticket breached(Instant now) {
    return new Ticket(
        id, client, version, subject, description, priority, status, requesterId, assigneeId, openedAt, dueAt, now,
        resolvedAt, resolution, comments);
  }

  /**
   * The assignee, when there is one.
   *
   * @return the assignee
   */
  public Optional<String> assignee() {
    return Optional.ofNullable(assigneeId);
  }
}
