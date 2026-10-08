package io.flowcatalyst.framework.usecase;

/**
 * The typed id of an aggregate: a record wrapping the id text, for example
 * {@code record OrderId(String value) implements AggregateId {}}.
 */
public interface AggregateId {

  /**
   * The id text, as stored and as written into event subjects.
   *
   * @return the text
   */
  String value();
}
