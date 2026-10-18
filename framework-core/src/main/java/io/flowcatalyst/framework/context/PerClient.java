package io.flowcatalyst.framework.context;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs work for one client after another, each with that client's {@link Context}: the pattern for a
 * scheduled job or a background loop that has no client of its own (core contract, section 3a).
 *
 * <p>One client's failure never stops the others: an exception is recorded for that client and the
 * run goes on. The run stops early, without starting the next client, when the thread is
 * interrupted (a shutdown); clients not started are absent from the result.
 */
public final class PerClient {

  private static final Logger LOG = LoggerFactory.getLogger(PerClient.class);

  /**
   * The work for one client.
   *
   * @param <R> what it returns
   */
  @FunctionalInterface
  public interface Task<R extends @Nullable Object> {

    /**
     * Does the work.
     *
     * @param context the base context with this client
     * @return the result
     * @throws Exception any failure; recorded for this client, the others still run
     */
    R run(Context context) throws Exception;
  }

  /**
   * What happened for one client.
   *
   * @param <R> the task's result
   */
  public sealed interface Outcome<R extends @Nullable Object> {

    /**
     * The task returned.
     *
     * @param <R> the result type
     * @param value the result
     */
    record Done<R extends @Nullable Object>(R value) implements Outcome<R> {}

    /**
     * The task threw.
     *
     * @param <R> the result type
     * @param cause what it threw
     */
    record Failed<R extends @Nullable Object>(Exception cause) implements Outcome<R> {}
  }

  private PerClient() {}

  /**
   * Runs the task for each client in iteration order.
   *
   * @param <R> the task's result
   * @param base the context to copy for each client (its principal, kind, application, correlation,
   *     causation, execution id and permissions are kept; see {@link Context#forClient})
   * @param clients the clients, from the application's own source; duplicates run once
   * @param task the work
   * @return the outcome per client that was started, in order
   */
  public static <R extends @Nullable Object> Map<ClientId, Outcome<R>> each(
      Context base, Collection<ClientId> clients, Task<R> task) {
    Map<ClientId, Outcome<R>> outcomes = new LinkedHashMap<>();
    for (ClientId client : clients) {
      if (Thread.currentThread().isInterrupted()) {
        break;
      }
      if (outcomes.containsKey(client)) {
        continue;
      }
      try {
        outcomes.put(client, new Outcome.Done<>(task.run(base.forClient(client))));
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        LOG.error("work for client {} failed", client, e);
        outcomes.put(client, new Outcome.Failed<>(e));
      }
    }
    return outcomes;
  }
}
