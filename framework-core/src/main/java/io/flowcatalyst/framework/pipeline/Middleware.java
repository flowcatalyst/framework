package io.flowcatalyst.framework.pipeline;

/**
 * One layer around a handler. It may inspect or replace the input, call {@code next} (or not, to
 * short-circuit), and inspect or replace the output.
 *
 * <p>Not specific to HTTP: the same shape wraps jobs and commands.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
@FunctionalInterface
public interface Middleware<I, O> {

  /**
   * Handles one input.
   *
   * @param input the input
   * @param next the rest of the chain; calling it more than once is allowed (a retry layer)
   * @return the output
   * @throws Exception any failure; it propagates to the middleware outside this one
   */
  O handle(I input, Next<I, O> next) throws Exception;
}
