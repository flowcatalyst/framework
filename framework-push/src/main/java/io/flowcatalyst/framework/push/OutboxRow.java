package io.flowcatalyst.framework.push;

import java.time.Instant;

/**
 * One row of {@code fw_push_outbox} as the relay takes it.
 *
 * @param id the outbox id
 * @param topic the topic
 * @param type the type
 * @param client {@code ""} for the platform client (NULL)
 * @param data the data
 * @param createdAt when the row was written
 */
public record OutboxRow(long id, String topic, String type, String client, String data, Instant createdAt) {}
