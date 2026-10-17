package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.usecase.Aggregate;

/**
 * A client's SLA: how many minutes an agent has to resolve a ticket, by priority. There is at most
 * one per client; a client that has not set one gets {@link #defaultMinutes(Priority)}. A change
 * applies to tickets opened afterwards: a ticket's deadline is fixed when it is opened.
 *
 * @param id the policy id
 * @param client the client it belongs to
 * @param version {@code 0} until stored
 * @param lowMinutes minutes for a {@link Priority#LOW} ticket
 * @param normalMinutes minutes for a {@link Priority#NORMAL} ticket
 * @param highMinutes minutes for a {@link Priority#HIGH} ticket
 * @param urgentMinutes minutes for an {@link Priority#URGENT} ticket
 */
public record SlaPolicy(
    SlaPolicyId id,
    ClientId client,
    long version,
    int lowMinutes,
    int normalMinutes,
    int highMinutes,
    int urgentMinutes)
    implements Aggregate {

  /**
   * What a client with no policy of its own gets.
   *
   * @param priority the ticket's priority
   * @return minutes to resolve
   */
  public static int defaultMinutes(Priority priority) {
    return switch (priority) {
      case LOW -> 2880;
      case NORMAL -> 1440;
      case HIGH -> 480;
      case URGENT -> 60;
    };
  }

  /**
   * A new policy.
   *
   * @param id its id
   * @param client the client
   * @param low minutes for a low ticket
   * @param normal minutes for a normal ticket
   * @param high minutes for a high ticket
   * @param urgent minutes for an urgent ticket
   * @return the policy, at version {@code 0}
   */
  public static SlaPolicy create(SlaPolicyId id, ClientId client, int low, int normal, int high, int urgent) {
    return new SlaPolicy(id, client, 0, low, normal, high, urgent);
  }

  /**
   * The policy with other minutes.
   *
   * @param low minutes for a low ticket
   * @param normal minutes for a normal ticket
   * @param high minutes for a high ticket
   * @param urgent minutes for an urgent ticket
   * @return the new policy
   */
  public SlaPolicy withMinutes(int low, int normal, int high, int urgent) {
    return new SlaPolicy(id, client, version, low, normal, high, urgent);
  }

  /**
   * Minutes to resolve a ticket of a priority.
   *
   * @param priority the priority
   * @return minutes
   */
  public int minutesFor(Priority priority) {
    return switch (priority) {
      case LOW -> lowMinutes;
      case NORMAL -> normalMinutes;
      case HIGH -> highMinutes;
      case URGENT -> urgentMinutes;
    };
  }
}
