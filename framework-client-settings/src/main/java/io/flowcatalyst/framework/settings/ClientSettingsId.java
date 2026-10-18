package io.flowcatalyst.framework.settings;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.usecase.AggregateId;

/**
 * The id of a client's settings: the client's code (there is one settings aggregate per client).
 *
 * @param value the client's code
 */
public record ClientSettingsId(String value) implements AggregateId {

  /**
   * The id for a client.
   *
   * @param client the client
   * @return its settings id
   */
  public static ClientSettingsId of(ClientId client) {
    return new ClientSettingsId(client.value());
  }
}
