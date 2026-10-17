package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.usecase.AuditData;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The commands of the ticket use cases. */
public final class Commands {

  private Commands() {}

  /**
   * Open a ticket.
   *
   * @param subject the one-line summary
   * @param description what is wrong
   * @param priority how urgent
   */
  public record OpenTicket(String subject, String description, Priority priority) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("subject", subject, "priority", priority.name());
    }
  }

  /**
   * Give a ticket to someone.
   *
   * @param ticketId the ticket
   * @param assigneeId the principal to work on it
   */
  public record AssignTicket(String ticketId, String assigneeId) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("ticketId", ticketId, "assigneeId", assigneeId);
    }
  }

  /**
   * Move a ticket along its status machine.
   *
   * @param ticketId the ticket
   * @param action what to do
   * @param note the resolution (for resolve) or the reason (for reopen); absent for the others
   */
  public record ChangeStatus(String ticketId, TicketAction action, @Nullable String note) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("ticketId", ticketId, "action", action.name());
    }
  }

  /**
   * Comment on a ticket.
   *
   * @param ticketId the ticket
   * @param body the text
   */
  public record AddComment(String ticketId, String body) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("ticketId", ticketId);
    }
  }

  /**
   * Mark a ticket whose SLA deadline has passed (the sweep's command).
   *
   * @param ticketId the ticket
   */
  public record FlagSlaBreach(String ticketId) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("ticketId", ticketId);
    }
  }

  /**
   * Set the client's SLA policy.
   *
   * @param lowMinutes minutes to resolve a low ticket
   * @param normalMinutes minutes for a normal ticket
   * @param highMinutes minutes for a high ticket
   * @param urgentMinutes minutes for an urgent ticket
   */
  public record SetSlaPolicy(int lowMinutes, int normalMinutes, int highMinutes, int urgentMinutes)
      implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("low", lowMinutes, "normal", normalMinutes, "high", highMinutes, "urgent", urgentMinutes);
    }
  }
}
