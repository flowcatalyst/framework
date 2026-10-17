package io.flowcatalyst.framework.examples.ticketstrial;

import java.util.List;

/**
 * The permissions of the application, in the platform's four-segment form
 * ({@code application:context:aggregate:action}). The API checks them on its operations, the use
 * cases check them again (a use case also runs from a receiver, where no permission is held), the
 * definition sync sends them in roles, and the SPA gates its screens on them.
 */
public final class Permissions {

  /** The application code, as on the platform. */
  public static final String APPLICATION = "tickets-trial";

  /** Read tickets and the SLA policy. */
  public static final String READ = "tickets-trial:support:ticket:read";

  /** Open a ticket. */
  public static final String OPEN = "tickets-trial:support:ticket:open";

  /** Work tickets: assign them and move them along their status machine. */
  public static final String WORK = "tickets-trial:support:ticket:work";

  /** Comment on a ticket (a requester only on their own). */
  public static final String COMMENT = "tickets-trial:support:ticket:comment";

  /** Change the client's SLA policy. */
  public static final String SLA_MANAGE = "tickets-trial:support:sla:manage";

  /** Every permission above, for the definitions. */
  public static final List<String> ALL = List.of(READ, OPEN, WORK, COMMENT, SLA_MANAGE);

  private Permissions() {}
}
