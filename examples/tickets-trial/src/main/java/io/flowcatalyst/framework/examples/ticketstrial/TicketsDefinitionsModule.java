package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.flowcatalyst.Definition;
import io.flowcatalyst.framework.flowcatalyst.Definitions;

/**
 * What the platform needs to know about the application, declared in code and pushed by {@code
 * java -jar app.jar sync} at deploy time: the event types the use cases write, the roles (the same
 * {@link Roles} the sign-in maps to permissions), the connection that delivers to this service, the
 * subscription that sends an assignment to {@link NotifyAssignee}, and the SLA sweep's schedule.
 */
public final class TicketsDefinitionsModule implements Module {

  /** The module id. */
  public static final String ID = "tickets-definitions";

  /** The code of the connection the subscription delivers through. */
  public static final String CONNECTION = "main";

  /** The scheduled job that flags tickets past their deadline. */
  public static final String SLA_SWEEP = "sla-sweep";

  /** The subscription that notifies the assignee. */
  public static final String NOTIFY_SUBSCRIPTION = "notify-assignee";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Definitions definitions =
        Definitions.in(app)
            .eventType(TicketEvents.OPENED.type(), "Ticket opened", "A requester opened a ticket.")
            .eventType(TicketEvents.ASSIGNED.type(), "Ticket assigned", "A ticket was given to an agent.")
            .eventType(TicketEvents.STATUS_CHANGED.type(), "Ticket status changed", "A ticket moved along its status machine.")
            .eventType(TicketEvents.COMMENTED.type(), "Ticket commented", "A comment was posted on a ticket.")
            .eventType(TicketEvents.SLA_BREACHED.type(), "Ticket SLA breached", "A ticket ran out of time.")
            .eventType(TicketEvents.POLICY_CHANGED.type(), "SLA policy changed", "A client changed its SLA policy.")
            .connection(CONNECTION, "Tickets")
            .subscription(
                Definition.Subscription.of(
                        NOTIFY_SUBSCRIPTION, "Notify the assignee", "/fc/dispatch", TicketEvents.ASSIGNED.type())
                    .viaConnection(CONNECTION)
                    .withDescription("Tells the agent a ticket was given to them."))
            // Every minute, seconds first (six fields). The platform reports each firing's completion.
            .scheduledJob(Definition.ScheduledJob.of(SLA_SWEEP, "SLA sweep", "0 * * * * *").tracking(true));
    for (Roles.RoleDefinition role : Roles.ALL) {
      definitions.role(Definition.Role.of(role.name(), role.permissions().stream().sorted().toArray(String[]::new)).withDescription(role.description()));
    }
  }
}
