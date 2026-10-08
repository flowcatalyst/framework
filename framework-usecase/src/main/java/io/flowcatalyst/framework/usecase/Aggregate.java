package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.ClientId;

/**
 * An aggregate: a record that holds what its rules need to decide. Methods that change it
 * return a new value and write nothing. Child collections belong to the aggregate and are
 * covered by its version.
 */
public interface Aggregate {

  /**
   * The aggregate's id.
   *
   * @return the typed id
   */
  AggregateId id();

  /**
   * The client the aggregate belongs to.
   *
   * @return the client
   */
  ClientId client();

  /**
   * The version the aggregate was loaded with.
   *
   * @return {@code 0} for an aggregate not yet stored, otherwise the stored version
   */
  long version();
}
