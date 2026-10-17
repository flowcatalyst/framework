package io.flowcatalyst.framework.examples.ticketstrial;

import static io.flowcatalyst.framework.http.Rules.maxLength;
import static io.flowcatalyst.framework.http.Rules.notBlank;
import static io.flowcatalyst.framework.http.Rules.notNull;
import static io.flowcatalyst.framework.http.Rules.range;

import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.TypeRef;
import io.flowcatalyst.framework.http.Validator;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The tickets API as typed operations (OpenAPI contract): values written once, declared by {@link
 * TicketsApiModule} in {@code register} and bound in {@code boot}. The committed document, {@code
 * web/openapi.json}, is written from them; the SPA's client is generated from that file.
 *
 * <p>The records are the wire types, separate from the aggregate: {@link Ticket} is not written to
 * the wire, and a computed field such as {@code overdue} belongs to the view.
 */
public final class TicketsApi {

  private TicketsApi() {}

  // ---- wire types -------------------------------------------------------------------------------

  /**
   * One comment of a ticket.
   *
   * @param commentId the comment's id
   * @param authorId who wrote it
   * @param body the text
   * @param postedAt when it was posted
   */
  public record CommentView(String commentId, String authorId, String body, Instant postedAt) {}

  /**
   * A ticket with its comments.
   *
   * @param id the ticket id
   * @param subject the one-line summary
   * @param description what is wrong
   * @param priority how urgent
   * @param status where it is in its life
   * @param version the optimistic-locking version
   * @param requesterId who opened it
   * @param assigneeId who has it, absent when nobody
   * @param openedAt when it was opened
   * @param dueAt the SLA deadline
   * @param overdue true when the SLA deadline passed while somebody still owed an answer
   * @param resolvedAt when it was resolved, absent when it is not
   * @param resolution how it was resolved, absent when it is not
   * @param comments the comments in the order they were posted
   */
  public record TicketView(
      String id,
      String subject,
      String description,
      Priority priority,
      TicketStatus status,
      long version,
      String requesterId,
      @Nullable String assigneeId,
      Instant openedAt,
      Instant dueAt,
      boolean overdue,
      @Nullable Instant resolvedAt,
      @Nullable String resolution,
      List<CommentView> comments) {

    static TicketView of(Ticket t, Instant now) {
      return new TicketView(
          t.id().value(),
          t.subject(),
          t.description(),
          t.priority(),
          t.status(),
          t.version(),
          t.requesterId(),
          t.assigneeId(),
          t.openedAt(),
          t.dueAt(),
          isOverdue(t.status(), t.dueAt(), t.breachedAt(), now),
          t.resolvedAt(),
          t.resolution(),
          t.comments().stream().map(c -> new CommentView(c.commentId(), c.authorId(), c.body(), c.postedAt())).toList());
    }
  }

  /**
   * A ticket in a list.
   *
   * @param id the ticket id
   * @param subject the one-line summary
   * @param priority how urgent
   * @param status where it is in its life
   * @param requesterId who opened it
   * @param assigneeId who has it, absent when nobody
   * @param openedAt when it was opened
   * @param dueAt the SLA deadline
   * @param overdue true when the SLA deadline passed while somebody still owed an answer
   * @param version the optimistic-locking version
   */
  public record TicketSummaryView(
      String id,
      String subject,
      Priority priority,
      TicketStatus status,
      String requesterId,
      @Nullable String assigneeId,
      Instant openedAt,
      Instant dueAt,
      boolean overdue,
      long version) {

    static TicketSummaryView of(TicketQueries.Summary s, Instant now) {
      return new TicketSummaryView(
          s.id(),
          s.subject(),
          s.priority(),
          s.status(),
          s.requesterId(),
          s.assigneeId(),
          s.openedAt(),
          s.dueAt(),
          isOverdue(s.status(), s.dueAt(), s.breachedAt(), now),
          s.version());
    }
  }

  /** Flagged by the sweep, or past the deadline and still waiting for an answer. */
  static boolean isOverdue(TicketStatus status, Instant dueAt, @Nullable Instant breachedAt, Instant now) {
    return breachedAt != null || (status.isActive() && dueAt.isBefore(now));
  }

  /**
   * One page of a list.
   *
   * @param <T> the item type
   * @param items the items
   * @param nextCursor pass it as {@code after} for the next page; absent on the last page
   */
  public record Page<T>(List<T> items, @Nullable String nextCursor) {}

  /**
   * Opens a ticket.
   *
   * @param subject the one-line summary
   * @param description what is wrong
   * @param priority how urgent
   */
  public record OpenTicketBody(String subject, String description, Priority priority) {
    static final Validator<OpenTicketBody> V =
        Validator.of(OpenTicketBody.class)
            .field("subject", OpenTicketBody::subject, notBlank(), maxLength(TicketUseCases.SUBJECT_MAX))
            .field("description", OpenTicketBody::description, maxLength(TicketUseCases.TEXT_MAX))
            .field("priority", OpenTicketBody::priority, notNull());
  }

  /**
   * Gives a ticket to someone.
   *
   * @param assigneeId the principal who should work on it
   */
  public record AssignBody(String assigneeId) {
    static final Validator<AssignBody> V =
        Validator.of(AssignBody.class).field("assigneeId", AssignBody::assigneeId, notBlank(), maxLength(100));
  }

  /**
   * Moves a ticket along its status machine.
   *
   * @param action what to do
   * @param note the resolution (for resolve) or the reason (for reopen)
   */
  public record TransitionBody(TicketAction action, @Nullable String note) {
    static final Validator<TransitionBody> V =
        Validator.of(TransitionBody.class)
            .field("action", TransitionBody::action, notNull())
            .field("note", TransitionBody::note, maxLength(TicketUseCases.TEXT_MAX));
  }

  /**
   * Comments on a ticket.
   *
   * @param body the text
   */
  public record CommentBody(String body) {
    static final Validator<CommentBody> V =
        Validator.of(CommentBody.class).field("body", CommentBody::body, notBlank(), maxLength(TicketUseCases.TEXT_MAX));
  }

  /**
   * A client's SLA policy.
   *
   * @param lowMinutes minutes to resolve a low ticket
   * @param normalMinutes minutes for a normal ticket
   * @param highMinutes minutes for a high ticket
   * @param urgentMinutes minutes for an urgent ticket
   * @param customised false while the client has set none and these are the defaults
   * @param version the optimistic-locking version, 0 while the client has set none
   */
  public record SlaPolicyView(
      int lowMinutes, int normalMinutes, int highMinutes, int urgentMinutes, boolean customised, long version) {}

  /**
   * Sets the client's SLA policy.
   *
   * @param lowMinutes minutes to resolve a low ticket
   * @param normalMinutes minutes for a normal ticket
   * @param highMinutes minutes for a high ticket
   * @param urgentMinutes minutes for an urgent ticket
   */
  public record SetSlaPolicyBody(int lowMinutes, int normalMinutes, int highMinutes, int urgentMinutes) {
    static final Validator<SetSlaPolicyBody> V =
        Validator.of(SetSlaPolicyBody.class)
            .field("lowMinutes", SetSlaPolicyBody::lowMinutes, range(1, TicketUseCases.MINUTES_MAX))
            .field("normalMinutes", SetSlaPolicyBody::normalMinutes, range(1, TicketUseCases.MINUTES_MAX))
            .field("highMinutes", SetSlaPolicyBody::highMinutes, range(1, TicketUseCases.MINUTES_MAX))
            .field("urgentMinutes", SetSlaPolicyBody::urgentMinutes, range(1, TicketUseCases.MINUTES_MAX));
  }

  // ---- parameters and operations ----------------------------------------------------------------

  /** The ticket in the path. */
  public static final PathParam<String> TICKET_ID = PathParam.string("ticketId");

  /** Only tickets with this status. */
  public static final QueryParam<@Nullable TicketStatus> STATUS = QueryParam.enumOf("status", TicketStatus.class).optional();

  /** Only tickets assigned to this principal. */
  public static final QueryParam<@Nullable String> ASSIGNEE = QueryParam.string("assignee").optional();

  /** Only tickets opened by this principal. */
  public static final QueryParam<@Nullable String> REQUESTER = QueryParam.string("requester").optional();

  /** How many tickets a page holds. */
  public static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(50);

  /** The {@code nextCursor} of the previous page. */
  public static final QueryParam<@Nullable String> AFTER = QueryParam.string("after").optional();

  /** {@code GET /api/tickets}. */
  public static final Operation<NoBody, Page<TicketSummaryView>> LIST =
      Operation.get("/api/tickets", new TypeRef<Page<TicketSummaryView>>() {})
          .id("listTickets")
          .summary("List the client's tickets, newest first")
          .tag("tickets")
          .query(STATUS, ASSIGNEE, REQUESTER, LIMIT, AFTER)
          .permission(Permissions.READ)
          .build();

  /** {@code GET /api/tickets/{ticketId}}. */
  public static final Operation<NoBody, TicketView> GET =
      Operation.get("/api/tickets/{ticketId}", TicketView.class)
          .id("getTicket")
          .summary("Read a ticket with its comments")
          .tag("tickets")
          .path(TICKET_ID)
          .permission(Permissions.READ)
          .error(404, "TICKET_NOT_FOUND", "there is no such ticket")
          .build();

  /** {@code POST /api/tickets}. */
  public static final Operation<OpenTicketBody, TicketView> OPEN =
      Operation.post("/api/tickets", OpenTicketBody.V, TicketView.class)
          .id("openTicket")
          .summary("Open a ticket")
          .tag("tickets")
          .status(201)
          .permission(Permissions.OPEN)
          .build();

  /** {@code POST /api/tickets/{ticketId}/assign}. */
  public static final Operation<AssignBody, TicketView> ASSIGN =
      Operation.post("/api/tickets/{ticketId}/assign", AssignBody.V, TicketView.class)
          .id("assignTicket")
          .summary("Give a ticket to someone")
          .tag("tickets")
          .path(TICKET_ID)
          .permission(Permissions.WORK)
          .error(404, "TICKET_NOT_FOUND", "there is no such ticket")
          .error(409, "TICKET_NOT_ASSIGNABLE", "a resolved or closed ticket cannot be assigned")
          .error(409, "ALREADY_ASSIGNED", "the ticket is already assigned to that person")
          .error(409, "STALE_AGGREGATE", "the ticket was changed by someone else; reload it and try again")
          .build();

  /** {@code POST /api/tickets/{ticketId}/transition}. */
  public static final Operation<TransitionBody, TicketView> TRANSITION =
      Operation.post("/api/tickets/{ticketId}/transition", TransitionBody.V, TicketView.class)
          .id("transitionTicket")
          .summary("Move a ticket along its status machine")
          .tag("tickets")
          .path(TICKET_ID)
          .permission(Permissions.WORK)
          .error(404, "TICKET_NOT_FOUND", "there is no such ticket")
          .error(409, "ILLEGAL_TRANSITION", "the action is not allowed from the ticket's status")
          .error(409, "ASSIGNEE_REQUIRED", "a ticket must be assigned before work starts on it")
          .error(409, "STALE_AGGREGATE", "the ticket was changed by someone else; reload it and try again")
          .build();

  /** {@code POST /api/tickets/{ticketId}/comments}. */
  public static final Operation<CommentBody, TicketView> COMMENT =
      Operation.post("/api/tickets/{ticketId}/comments", CommentBody.V, TicketView.class)
          .id("commentOnTicket")
          .summary("Comment on a ticket")
          .tag("tickets")
          .path(TICKET_ID)
          .permission(Permissions.COMMENT)
          .error(403, "NOT_YOUR_TICKET", "a requester may comment only on tickets they opened")
          .error(404, "TICKET_NOT_FOUND", "there is no such ticket")
          .error(409, "TICKET_CLOSED", "a closed ticket cannot change")
          .error(429, "RATE_LIMITED", "too many comments from one person; wait and try again")
          .build();

  /** {@code GET /api/sla-policy}. */
  public static final Operation<NoBody, SlaPolicyView> GET_SLA =
      Operation.get("/api/sla-policy", SlaPolicyView.class)
          .id("getSlaPolicy")
          .summary("Read the client's SLA policy (the defaults until it sets one)")
          .tag("sla")
          .permission(Permissions.READ)
          .build();

  /** {@code PUT /api/sla-policy}. */
  public static final Operation<SetSlaPolicyBody, SlaPolicyView> SET_SLA =
      Operation.put("/api/sla-policy", SetSlaPolicyBody.V, SlaPolicyView.class)
          .id("setSlaPolicy")
          .summary("Set the client's SLA policy; tickets opened afterwards use it")
          .tag("sla")
          .permission(Permissions.SLA_MANAGE)
          .error(409, "STALE_AGGREGATE", "the policy was changed by someone else; reload it and try again")
          .build();
}
