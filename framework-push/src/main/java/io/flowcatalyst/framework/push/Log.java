package io.flowcatalyst.framework.push;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The log driver (push contract, section 4): where the relay appends pushes and every instance's
 * hub reads them. A1 ({@link PostgresLog}, the default) keeps it in the table
 * {@code fw_push_log}; A2 keeps it in a Redis stream. The hub, the relay and the HTTP layer only
 * use this interface. Implementations are safe for concurrent use.
 */
public interface Log {

  /**
   * Logs rows (the relay's batch, in outbox id order) in that order, with ids above every id logged
   * before, stamped {@code at}. {@code tx} is the relay's transaction, which deletes the rows from
   * {@code fw_push_outbox} after this and then commits: A1 appends inside it (one transaction, so a
   * failure appends nothing), and a driver outside the database appends at once, so a crash before
   * the commit appends the rows again (it must keep {@link Entry#outboxId} for the hub). Also wakes
   * every follower once the rows are visible.
   *
   * @param tx the relay's transaction
   * @param rows the rows, in outbox id order
   * @param at when they are logged
   * @throws LogException when the log fails
   */
  void append(Connection tx, List<OutboxRow> rows, Instant at);

  /**
   * At most {@code limit} entries with an id above {@code after}, in id order.
   *
   * @param after the id to read after
   * @param limit the most entries
   * @return the entries
   * @throws LogException when the log fails
   */
  List<Entry> readAfter(long after, int limit);

  /**
   * The newest {@code limit} entries (fewer when the log holds fewer), in id order: what a starting
   * hub puts in its memory of recent pushes, in one read.
   *
   * @param limit the most entries
   * @return the entries
   * @throws LogException when the log fails
   */
  List<Entry> newest(int limit);

  /**
   * The highest id logged.
   *
   * @return the id; 0 when nothing was logged
   * @throws LogException when the log fails
   */
  long latest();

  /**
   * The highest id the log no longer keeps, or may no longer keep (0 when nothing was deleted): a
   * reader after an id below it may have missed pushes and gets a reset. A1: the oldest id kept
   * minus one (its ids have no gaps); A2: the stream's max-deleted-entry-id.
   *
   * @return the id
   * @throws LogException when the log fails
   */
  long trimmed();

  /**
   * Deletes the entries logged before {@code before}, always keeping the newest (so ids keep
   * increasing).
   *
   * @param before the cut-off
   * @return how many entries it deleted
   * @throws LogException when the log fails
   */
  long prune(Instant before);

  /**
   * Blocks until {@code stopping} turns true (then returns), calling {@link Follower#grown} whenever
   * the log may have grown, once right after it starts following, and {@link Follower#end} for every
   * {@link #end} of any instance.
   *
   * @param follower what to call
   * @param stopping whether to stop; checked at least every half second
   * @throws LogException when it loses its connection; the hub calls it again after a pause and
   *     polls meanwhile
   */
  void follow(Follower follower, BooleanSupplier stopping);

  /**
   * Asks every instance (this one included) to end the streams of a holder.
   *
   * @param holder the holder
   * @throws LogException when the log fails
   */
  void end(Holder holder);

  /**
   * Checks that the log can be reached (the health check {@code push:log}).
   *
   * @throws LogException when it cannot
   */
  void ping();
}
