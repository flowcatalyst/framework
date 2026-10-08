package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.usecase.AggregateId;

/**
 * The id of an order, {@code ord_} plus a TSID.
 *
 * @param value the id text
 */
public record OrderId(String value) implements AggregateId {}
