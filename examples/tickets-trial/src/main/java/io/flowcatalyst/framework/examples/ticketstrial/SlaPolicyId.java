package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.usecase.AggregateId;

/**
 * The id of a client's SLA policy, {@code sla_} plus a TSID.
 *
 * @param value the id text
 */
public record SlaPolicyId(String value) implements AggregateId {}
