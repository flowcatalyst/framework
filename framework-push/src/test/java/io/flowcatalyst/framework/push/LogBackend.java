package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.core.Module;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Where a test's nodes keep the log: the contract tests of push contract section 14 run once per
 * driver, and a driver's module supplies its backend (the Postgres one is here, the Redis one in
 * {@code framework-push-redis}). The outbox and the locks are always on the Postgres database.
 */
interface LogBackend extends AutoCloseable {

  /** Sections added to a node's {@code application.yaml} (empty for the Postgres driver). */
  default String yaml() {
    return "";
  }

  /** Environment added to a node's. */
  default Map<String, String> env() {
    return Map.of();
  }

  /** Modules a node lists before the push module. */
  default List<Module> modules() {
    return List.of();
  }

  /** Hands the driver to the push module's builder. */
  default void builder(PushModule.Builder builder) {}

  /** A log on the backend without an application around it (the relay tests). */
  Log bare(DataSource database);

  /** A backend for another test case that shares nothing with this one's log. */
  default LogBackend fresh() {
    return this;
  }

  /** Whether log ids have no gaps (A1: {@code max(id) + 1}; A2: the Redis entry ids). */
  boolean contiguousIds();

  /**
   * How an entry of the platform client is stored by the driver: {@code "<null>"} for A1's NULL
   * column, {@code ""} for A2's empty field.
   */
  String platformClientStored();

  /** The client of each entry as the driver stores it, in id order. */
  List<String> storedClients(TestNode node);

  /** Deletes the entries with an id up to {@code id}, as retention would (and what the driver then reports as trimmed). */
  void deleteUpTo(TestNode node, long id);

  /**
   * Writes the replay vector's entries into the log with their ids; {@code trimmed} is the highest
   * id retention deleted.
   */
  void load(TestNode node, List<Entry> entries, long trimmed);

  /** The time the driver's own clock reads at the start of a test: A1 stamps the relay's clock, A2 uses the server's. */
  default java.time.Instant clockStart() {
    return TestNode.BEGIN;
  }

  @Override
  default void close() {}
}
