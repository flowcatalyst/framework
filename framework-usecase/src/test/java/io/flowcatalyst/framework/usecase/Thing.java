package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.ClientId;

/** A tiny aggregate for the runner's tests: one table, no children. */
record Thing(ThingId id, ClientId client, long version, String name, String code)
    implements Aggregate {

  static Thing create(ThingId id, ClientId client, String name, String code) {
    return new Thing(id, client, 0, name, code);
  }

  Thing withName(String value) {
    return new Thing(id, client, version, value, code);
  }

  Thing withCode(String value) {
    return new Thing(id, client, version, name, value);
  }
}
