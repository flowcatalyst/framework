package io.flowcatalyst.framework.push;

import java.time.Instant;

/**
 * One push in the log (push contract, section 4): its id (the SSE event id, increasing), what was
 * pushed, and when it was logged.
 *
 * @param id the log id: positive and increasing (A1 contiguous)
 * @param topic the topic
 * @param type the type
 * @param client the client the push was written for; {@code ""} is the platform client (NULL in the
 *     tables), whose pushes go to every stream on the topic
 * @param data the push's JSON object, compact, as it was written
 * @param loggedAt when the relay logged it
 * @param outboxId the outbox row it came from, for a driver that can log one row twice (A2); 0 when
 *     the driver does not keep it (A1). The hub drops an outbox id it has already seen.
 */
public record Entry(
    long id, String topic, String type, String client, String data, Instant loggedAt, long outboxId) {

  /**
   * An entry without an outbox id.
   *
   * @param id the log id
   * @param topic the topic
   * @param type the type
   * @param client the client, {@code ""} for the platform client
   * @param data the data
   * @param loggedAt when it was logged
   */
  public Entry(long id, String topic, String type, String client, String data, Instant loggedAt) {
    this(id, topic, type, client, data, loggedAt, 0);
  }
}
