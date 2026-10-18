package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.settings.ClientSetting;
import java.util.Optional;

/**
 * The service's per-client settings (client-settings contract): defined here, in code; the values
 * are rows of {@code fw_client_settings}. The SLA is what used to be a table, a repository, SQL and an
 * aggregate of the service's own (friction F6).
 */
public final class TicketSettings {

  /** Most minutes a priority may be given: 30 days. */
  public static final int MINUTES_MAX = 43_200;

  /**
   * A client's SLA: minutes an agent has to resolve a ticket, by priority. A change applies to
   * tickets opened afterwards: a ticket's deadline is fixed when it is opened.
   *
   * @param low minutes for a {@link Priority#LOW} ticket
   * @param normal minutes for a {@link Priority#NORMAL} ticket
   * @param high minutes for a {@link Priority#HIGH} ticket
   * @param urgent minutes for an {@link Priority#URGENT} ticket
   */
  public record SlaMinutes(int low, int normal, int high, int urgent) {

    /**
     * Minutes to resolve a ticket of a priority.
     *
     * @param priority the priority
     * @return minutes
     */
    public int minutesFor(Priority priority) {
      return switch (priority) {
        case LOW -> low;
        case NORMAL -> normal;
        case HIGH -> high;
        case URGENT -> urgent;
      };
    }
  }

  /**
   * Why a set of minutes is refused.
   *
   * @param code the error code
   * @param message safe to show a person
   */
  public record Problem(String code, String message) {}

  /** What a client with no SLA of its own gets. */
  public static final SlaMinutes DEFAULT_SLA = new SlaMinutes(2880, 1440, 480, 60);

  /** The client's SLA. */
  public static final ClientSetting<SlaMinutes> SLA =
      ClientSetting.of("tickets.sla", SlaMinutes.class, DEFAULT_SLA).rule(m -> problem(m).map(Problem::message));

  private TicketSettings() {}

  /**
   * The first thing wrong with an SLA: a time outside 1 to {@link #MINUTES_MAX}, or a more urgent
   * priority given more time.
   *
   * @param m the minutes
   * @return the problem, or empty when it is acceptable
   */
  public static Optional<Problem> problem(SlaMinutes m) {
    for (int minutes : new int[] {m.low(), m.normal(), m.high(), m.urgent()}) {
      if (minutes < 1 || minutes > MINUTES_MAX) {
        return Optional.of(new Problem("MINUTES_INVALID", "Each time is 1 to " + MINUTES_MAX + " minutes."));
      }
    }
    if (!(m.urgent() <= m.high() && m.high() <= m.normal() && m.normal() <= m.low())) {
      return Optional.of(new Problem("POLICY_NOT_ORDERED", "A more urgent ticket cannot be given more time."));
    }
    return Optional.empty();
  }
}
