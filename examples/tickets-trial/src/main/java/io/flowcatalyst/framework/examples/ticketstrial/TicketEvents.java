package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.EventMetadata;
import io.flowcatalyst.framework.usecase.EventType;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The domain events of the ticket use cases. */
public final class TicketEvents {

  /** {@code tickets-trial:support:ticket:opened}. */
  public static final EventType OPENED = new EventType("tickets-trial", "support", "ticket", "opened");

  /** {@code tickets-trial:support:ticket:assigned}: the platform delivers it to the notification handler. */
  public static final EventType ASSIGNED = new EventType("tickets-trial", "support", "ticket", "assigned");

  /** {@code tickets-trial:support:ticket:status-changed}. */
  public static final EventType STATUS_CHANGED = new EventType("tickets-trial", "support", "ticket", "status-changed");

  /** {@code tickets-trial:support:ticket:commented}. */
  public static final EventType COMMENTED = new EventType("tickets-trial", "support", "ticket", "commented");

  /** {@code tickets-trial:support:ticket:sla-breached}. */
  public static final EventType SLA_BREACHED = new EventType("tickets-trial", "support", "ticket", "sla-breached");

  /** {@code tickets-trial:support:sla-policy:changed}. */
  public static final EventType POLICY_CHANGED = new EventType("tickets-trial", "support", "sla-policy", "changed");

  private TicketEvents() {}

  /**
   * A ticket was opened.
   *
   * @param metadata the event metadata
   * @param ticketId the ticket
   * @param subject its summary
   * @param priority how urgent
   * @param requesterId who opened it
   * @param dueAt the SLA deadline
   */
  public record TicketOpened(
      EventMetadata metadata, String ticketId, String subject, Priority priority, String requesterId, Instant dueAt)
      implements DomainEvent {}

  /**
   * A ticket was given to someone.
   *
   * @param metadata the event metadata
   * @param ticketId the ticket
   * @param subject its summary, for the notification
   * @param assigneeId who has it now
   * @param previousAssigneeId who had it, if anyone
   * @param assignedBy who did it
   */
  public record TicketAssigned(
      EventMetadata metadata,
      String ticketId,
      String subject,
      String assigneeId,
      @Nullable String previousAssigneeId,
      String assignedBy)
      implements DomainEvent {}

  /**
   * A ticket moved along its status machine.
   *
   * @param metadata the event metadata
   * @param ticketId the ticket
   * @param action what was done
   * @param from the status before
   * @param to the status after
   * @param note the resolution or the reason, if one was given
   */
  public record TicketStatusChanged(
      EventMetadata metadata,
      String ticketId,
      TicketAction action,
      TicketStatus from,
      TicketStatus to,
      @Nullable String note)
      implements DomainEvent {}

  /**
   * A comment was posted.
   *
   * @param metadata the event metadata
   * @param ticketId the ticket
   * @param commentId the comment
   * @param authorId who wrote it
   */
  public record TicketCommented(EventMetadata metadata, String ticketId, String commentId, String authorId)
      implements DomainEvent {}

  /**
   * A ticket ran out of time.
   *
   * @param metadata the event metadata
   * @param ticketId the ticket
   * @param subject its summary
   * @param priority how urgent it is
   * @param dueAt the deadline it missed
   * @param assigneeId who has it, if anyone
   */
  public record TicketSlaBreached(
      EventMetadata metadata, String ticketId, String subject, Priority priority, Instant dueAt, @Nullable String assigneeId)
      implements DomainEvent {}

  /**
   * A client changed its SLA policy.
   *
   * @param metadata the event metadata
   * @param policyId the policy
   * @param lowMinutes minutes for a low ticket
   * @param normalMinutes minutes for a normal ticket
   * @param highMinutes minutes for a high ticket
   * @param urgentMinutes minutes for an urgent ticket
   */
  public record SlaPolicyChanged(
      EventMetadata metadata, String policyId, int lowMinutes, int normalMinutes, int highMinutes, int urgentMinutes)
      implements DomainEvent {}
}
