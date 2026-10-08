package io.flowcatalyst.framework.pipeline;

/**
 * The rest of a {@link Pipeline}, as seen from one {@link Middleware}: everything inside it.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
@FunctionalInterface
public interface Next<I, O> {

  /**
   * Runs the rest of the chain.
   *
   * @param input the input, possibly replaced by the calling middleware
   * @return the output of the rest of the chain
   * @throws Exception whatever the rest of the chain throws
   */
  O handle(I input) throws Exception;
}
