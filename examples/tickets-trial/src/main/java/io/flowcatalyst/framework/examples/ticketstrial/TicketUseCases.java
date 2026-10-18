package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.AddComment;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.AssignTicket;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.ChangeStatus;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.FlagSlaBreach;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.OpenTicket;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.SetSlaPolicy;
import io.flowcatalyst.framework.examples.ticketstrial.TicketEvents.SlaPolicyChanged;
import io.flowcatalyst.framework.examples.ticketstrial.TicketSettings.SlaMinutes;
import io.flowcatalyst.framework.examples.ticketstrial.TicketEvents.TicketAssigned;
import io.flowcatalyst.framework.examples.ticketstrial.TicketEvents.TicketCommented;
import io.flowcatalyst.framework.examples.ticketstrial.TicketEvents.TicketOpened;
import io.flowcatalyst.framework.examples.ticketstrial.TicketEvents.TicketSlaBreached;
import io.flowcatalyst.framework.examples.ticketstrial.TicketEvents.TicketStatusChanged;
import io.flowcatalyst.framework.usecase.Check;
import io.flowcatalyst.framework.settings.ClientSettings;
import io.flowcatalyst.framework.settings.ClientSettingsRepository;
import io.flowcatalyst.framework.usecase.ConflictPolicy;
import io.flowcatalyst.framework.usecase.Decision;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseError;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.Optional;

/**
 * The use cases on a ticket and on a client's SLA policy, each defined once: validate the command's
 * shape, authorize, then execute (load once, apply the rules, return a plan). Run them with
 * {@link io.flowcatalyst.framework.usecase.UseCases#run}. They are the only way data changes, from
 * the HTTP API and from the receivers alike, so each checks the permission it needs itself: a
 * receiver holds none and runs as the application's service principal instead.
 */
public final class TicketUseCases {

  /** The longest subject, description and note, which the table's CHECKs repeat. */
  static final int SUBJECT_MAX = 200;

  static final int TEXT_MAX = 4000;

  /** The longest SLA, in minutes: thirty days. */
  static final int MINUTES_MAX = TicketSettings.MINUTES_MAX;

  /**
   * The conflict policy of each use case that edits an existing record.
   *
   * @param assign what to do when the ticket changed under an {@code assign}
   * @param change what to do when the ticket changed under a {@code changeStatus}
   * @param comment what to do when the ticket changed under an {@code addComment}
   * @param flag what to do when the ticket changed under a {@code flagSlaBreach}
   * @param policy what to do when the policy changed under a {@code setSlaPolicy}
   */
  public record Policies(
      ConflictPolicy assign, ConflictPolicy change, ConflictPolicy comment, ConflictPolicy flag, ConflictPolicy policy) {

    /**
     * Assigning and commenting are safe to repeat on fresh state, so they retry; a status change
     * made on a stale view is for the caller to decide again, so it fails (and so does a policy
     * edit: the person editing should see what changed). The sweep's flag retries: nobody is
     * waiting to decide.
     *
     * @return the defaults
     */
    public static Policies defaults() {
      ConflictPolicy retry = ConflictPolicy.retry(3, Duration.ofMillis(25));
      return new Policies(retry, ConflictPolicy.fail(), retry, retry, ConflictPolicy.fail());
    }
  }

  private final TicketRepository tickets;
  private final ClientSettingsRepository settings;
  private final Database database;
  private final Events events;
  private final Ids ids;
  private final InstantSource clock;
  private final UseCase<OpenTicket, TicketOpened> openTicket;
  private final UseCase<AssignTicket, TicketAssigned> assignTicket;
  private final UseCase<ChangeStatus, TicketStatusChanged> changeStatus;
  private final UseCase<AddComment, TicketCommented> addComment;
  private final UseCase<FlagSlaBreach, TicketSlaBreached> flagSlaBreach;
  private final UseCase<SetSlaPolicy, SlaPolicyChanged> setSlaPolicy;

  /**
   * Defines the use cases.
   *
   * @param tickets the ticket repository
   * @param settings the per-client settings repository (the SLA is one of them)
   * @param database the database the use cases read from
   * @param events makes event metadata
   * @param ids generates ids
   * @param clock the time of the application
   * @param conflicts the conflict policies
   */
  public TicketUseCases(
      TicketRepository tickets,
      ClientSettingsRepository settings,
      Database database,
      Events events,
      Ids ids,
      InstantSource clock,
      Policies conflicts) {
    this.tickets = tickets;
    this.settings = settings;
    this.database = database;
    this.events = events;
    this.ids = ids;
    this.clock = clock;
    this.openTicket = defineOpenTicket();
    this.assignTicket = defineAssignTicket(conflicts.assign());
    this.changeStatus = defineChangeStatus(conflicts.change());
    this.addComment = defineAddComment(conflicts.comment());
    this.flagSlaBreach = defineFlagSlaBreach(conflicts.flag());
    this.setSlaPolicy = defineSetSlaPolicy(conflicts.policy());
  }

  /**
   * Open a ticket.
   *
   * @return the use case
   */
  public UseCase<OpenTicket, TicketOpened> openTicket() {
    return openTicket;
  }

  /**
   * Give a ticket to someone.
   *
   * @return the use case
   */
  public UseCase<AssignTicket, TicketAssigned> assignTicket() {
    return assignTicket;
  }

  /**
   * Move a ticket along its status machine.
   *
   * @return the use case
   */
  public UseCase<ChangeStatus, TicketStatusChanged> changeStatus() {
    return changeStatus;
  }

  /**
   * Comment on a ticket.
   *
   * @return the use case
   */
  public UseCase<AddComment, TicketCommented> addComment() {
    return addComment;
  }

  /**
   * Mark a ticket that ran out of time (run by the SLA sweep).
   *
   * @return the use case
   */
  public UseCase<FlagSlaBreach, TicketSlaBreached> flagSlaBreach() {
    return flagSlaBreach;
  }

  /**
   * Set the client's SLA policy.
   *
   * @return the use case
   */
  public UseCase<SetSlaPolicy, SlaPolicyChanged> setSlaPolicy() {
    return setSlaPolicy;
  }

  // ---- authorization: who may run what ----------------------------------------------------------

  /** A signed-in person holding the permission. */
  private static Check<Object> requires(String permission) {
    return (context, command) -> {
      if (context.principal() == null) {
        return Optional.of(UseCaseError.authorization("PRINCIPAL_REQUIRED", "Sign in to do this."));
      }
      return context.hasPermission(permission)
          ? Optional.empty()
          : Optional.of(UseCaseError.authorization("FORBIDDEN", "You may not do this."));
    };
  }

  /** The application's own service principal: what the platform's receivers run as. */
  private static final Check<Object> SERVICE_ONLY =
      (context, command) ->
          context.principalKind() == PrincipalKind.SERVICE
              ? Optional.empty()
              : Optional.of(UseCaseError.authorization("NOT_A_SERVICE", "Only the application's service does this."));

  // ---- the use cases ----------------------------------------------------------------------------

  private UseCase<OpenTicket, TicketOpened> defineOpenTicket() {
    return UseCase.named("open-ticket", OpenTicket.class, TicketOpened.class)
        .validate(
            (context, command) -> {
              if (command.subject().isBlank()) {
                return Optional.of(UseCaseError.validation("SUBJECT_REQUIRED", "A subject is required."));
              }
              if (command.subject().length() > SUBJECT_MAX) {
                return Optional.of(UseCaseError.validation("SUBJECT_TOO_LONG", "The subject is too long."));
              }
              if (command.description().length() > TEXT_MAX) {
                return Optional.of(UseCaseError.validation("DESCRIPTION_TOO_LONG", "The description is too long."));
              }
              return Optional.empty();
            })
        .authorize(requires(Permissions.OPEN))
        .execute(
            (context, command) -> {
              Instant now = clock.instant();
              // The client's own SLA if it set one, else the default: read once, here.
              int minutes = settings.forClient(database, context.client()).get(TicketSettings.SLA).minutesFor(command.priority());
              String requester = requester(context);
              Ticket ticket =
                  Ticket.open(
                      new TicketId(ids.generate("tkt")),
                      context.client(),
                      command.subject().strip(),
                      command.description().strip(),
                      command.priority(),
                      requester,
                      now,
                      now.plus(Duration.ofMinutes(minutes)));
              return Plan.save(
                  ticket,
                  tickets,
                  new TicketOpened(
                      events.metadata(context, TicketEvents.OPENED, ticket.id()),
                      ticket.id().value(),
                      ticket.subject(),
                      ticket.priority(),
                      requester,
                      ticket.dueAt()));
            });
  }

  private UseCase<AssignTicket, TicketAssigned> defineAssignTicket(ConflictPolicy policy) {
    return UseCase.named("assign-ticket", AssignTicket.class, TicketAssigned.class)
        .validate(
            (context, command) ->
                command.assigneeId().isBlank() || command.assigneeId().length() > 100
                    ? Optional.of(UseCaseError.validation("ASSIGNEE_REQUIRED", "Name who should work on the ticket."))
                    : Optional.empty())
        .authorize(requires(Permissions.WORK))
        .conflict(policy)
        .load((context, command) -> load(context, command.ticketId()), TICKET_NOT_FOUND)
        .execute(
            (context, command, ticket) -> {
              if (!ticket.status().isActive()) {
                return Decision.reject(
                    UseCaseError.businessRule("TICKET_NOT_ASSIGNABLE", "A resolved or closed ticket cannot be assigned."));
              }
              if (command.assigneeId().equals(ticket.assigneeId())) {
                return Decision.reject(
                    UseCaseError.businessRule("ALREADY_ASSIGNED", "The ticket is already assigned to that person."));
              }
              Ticket updated = ticket.assignedTo(command.assigneeId());
              return Plan.save(
                  updated,
                  tickets,
                  new TicketAssigned(
                      events.metadata(context, TicketEvents.ASSIGNED, ticket.id()),
                      ticket.id().value(),
                      ticket.subject(),
                      command.assigneeId(),
                      ticket.assigneeId(),
                      requester(context)));
            });
  }

  private UseCase<ChangeStatus, TicketStatusChanged> defineChangeStatus(ConflictPolicy policy) {
    return UseCase.named("change-status", ChangeStatus.class, TicketStatusChanged.class)
        .validate(
            (context, command) -> {
              boolean needsNote = command.action() == TicketAction.RESOLVE || command.action() == TicketAction.REOPEN;
              String note = command.note();
              if (needsNote && (note == null || note.isBlank())) {
                return Optional.of(
                    UseCaseError.validation(
                        "NOTE_REQUIRED",
                        command.action() == TicketAction.RESOLVE ? "Say how it was resolved." : "Say why it is reopened."));
              }
              if (note != null && note.length() > TEXT_MAX) {
                return Optional.of(UseCaseError.validation("NOTE_TOO_LONG", "The note is too long."));
              }
              return Optional.empty();
            })
        .authorize(requires(Permissions.WORK))
        .conflict(policy)
        .load((context, command) -> load(context, command.ticketId()), TICKET_NOT_FOUND)
        .execute(
            (context, command, ticket) -> {
              Optional<TicketStatus> next = ticket.status().after(command.action());
              if (next.isEmpty()) {
                return Decision.reject(
                    UseCaseError.businessRule(
                            "ILLEGAL_TRANSITION", "A " + ticket.status() + " ticket cannot be moved by " + command.action() + ".")
                        .withDetails(Map.of("status", ticket.status().name(), "action", command.action().name())));
              }
              if (command.action() == TicketAction.START && ticket.assigneeId() == null) {
                return Decision.reject(
                    UseCaseError.businessRule("ASSIGNEE_REQUIRED", "Assign the ticket before starting work on it."));
              }
              String note = command.note() == null ? null : command.note().strip();
              Ticket updated = ticket.moved(next.get(), command.action(), note, clock.instant());
              return Plan.save(
                  updated,
                  tickets,
                  new TicketStatusChanged(
                      events.metadata(context, TicketEvents.STATUS_CHANGED, ticket.id()),
                      ticket.id().value(),
                      command.action(),
                      ticket.status(),
                      next.get(),
                      note));
            });
  }

  private UseCase<AddComment, TicketCommented> defineAddComment(ConflictPolicy policy) {
    return UseCase.named("add-comment", AddComment.class, TicketCommented.class)
        .validate(
            (context, command) ->
                command.body().isBlank() || command.body().length() > TEXT_MAX
                    ? Optional.of(UseCaseError.validation("BODY_INVALID", "A comment is 1 to " + TEXT_MAX + " characters."))
                    : Optional.empty())
        .authorize(requires(Permissions.COMMENT))
        .conflict(policy)
        .load((context, command) -> load(context, command.ticketId()), TICKET_NOT_FOUND)
        // The record-level rule: a requester may comment only on the tickets they opened; whoever
        // works tickets, on any. It needs the ticket, so it runs after the load, before execute.
        .authorizeLoaded(
            (context, command, ticket) ->
                context.hasPermission(Permissions.WORK) || requester(context).equals(ticket.requesterId())
                    ? Optional.empty()
                    : Optional.of(UseCaseError.authorization("NOT_YOUR_TICKET", "You may comment only on tickets you opened.")))
        .execute(
            (context, command, ticket) -> {
              String author = requester(context);
              if (ticket.status() == TicketStatus.CLOSED) {
                return Decision.reject(UseCaseError.businessRule("TICKET_CLOSED", "A closed ticket cannot change."));
              }
              Comment comment = new Comment(ids.generate("cmt"), author, command.body().strip(), clock.instant());
              Ticket updated = ticket.withComment(comment);
              return Plan.save(
                  updated,
                  tickets,
                  new TicketCommented(
                      events.metadata(context, TicketEvents.COMMENTED, ticket.id()),
                      ticket.id().value(),
                      comment.commentId(),
                      author));
            });
  }

  private UseCase<FlagSlaBreach, TicketSlaBreached> defineFlagSlaBreach(ConflictPolicy policy) {
    return UseCase.named("flag-sla-breach", FlagSlaBreach.class, TicketSlaBreached.class)
        .authorize(SERVICE_ONLY)
        .conflict(policy)
        .load((context, command) -> load(context, command.ticketId()), TICKET_NOT_FOUND)
        .execute(
            (context, command, ticket) -> {
              Instant now = clock.instant();
              // Idempotent: the sweep may see a ticket twice, and a ticket may have been resolved
              // since the sweep looked.
              if (ticket.breachedAt() != null || !ticket.status().isActive() || !ticket.dueAt().isBefore(now)) {
                return Decision.reject(UseCaseError.businessRule("NOT_OVERDUE", "The ticket is not overdue."));
              }
              return Plan.save(
                  ticket.breached(now),
                  tickets,
                  new TicketSlaBreached(
                      events.metadata(context, TicketEvents.SLA_BREACHED, ticket.id()),
                      ticket.id().value(),
                      ticket.subject(),
                      ticket.priority(),
                      ticket.dueAt(),
                      ticket.assigneeId()));
            });
  }

  private UseCase<SetSlaPolicy, SlaPolicyChanged> defineSetSlaPolicy(ConflictPolicy policy) {
    return UseCase.named("set-sla-policy", SetSlaPolicy.class, SlaPolicyChanged.class)
        .validate(
            (context, c) ->
                TicketSettings.problem(minutes(c)).map(p -> UseCaseError.validation(p.code(), p.message())))
        .authorize(requires(Permissions.SLA_MANAGE))
        .conflict(policy)
        .execute(
            (context, c) -> {
              // The client's settings row (or an empty one for a client that never set anything), with
              // the SLA replaced: the framework's aggregate, saved like any other.
              ClientSettings saved = settings.forClient(database, context.client()).with(TicketSettings.SLA, minutes(c));
              return Plan.save(
                  saved,
                  settings,
                  new SlaPolicyChanged(
                      events.metadata(context, TicketEvents.POLICY_CHANGED, saved.id()),
                      saved.id().value(),
                      c.lowMinutes(),
                      c.normalMinutes(),
                      c.highMinutes(),
                      c.urgentMinutes()));
            });
  }

  private static SlaMinutes minutes(SetSlaPolicy c) {
    return new SlaMinutes(c.lowMinutes(), c.normalMinutes(), c.highMinutes(), c.urgentMinutes());
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private Optional<Ticket> load(Context context, String ticketId) throws SQLException {
    return tickets.find(database, context.client(), new TicketId(ticketId));
  }

  /** The acting principal. Every use case that calls it has authorized a signed-in caller first. */
  private static String requester(Context context) {
    PrincipalId principal = context.principal();
    if (principal == null) {
      throw new IllegalStateException("authorize admits only a signed-in principal");
    }
    return principal.value();
  }

  private static final UseCaseError TICKET_NOT_FOUND = UseCaseError.notFound("TICKET_NOT_FOUND", "There is no such ticket.");
}
