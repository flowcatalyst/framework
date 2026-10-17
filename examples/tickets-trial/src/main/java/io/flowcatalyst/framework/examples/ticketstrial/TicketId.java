package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.usecase.AggregateId;

/**
 * The id of a ticket, {@code tkt_} plus a TSID.
 *
 * @param value the id text
 */
public record TicketId(String value) implements AggregateId {}
