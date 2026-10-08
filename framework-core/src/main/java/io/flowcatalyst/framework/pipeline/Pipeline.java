package io.flowcatalyst.framework.pipeline;

import java.util.List;
import java.util.Objects;

/**
 * Middleware composed around a terminal handler. The first middleware in the list is the
 * outermost. A pipeline is itself a {@link Next}, so pipelines nest.
 *
 * <pre>{@code
 * Pipeline<Request, Response> p = Pipeline.of(List.of(logging, auth), handler);
 * Response r = p.handle(request); // logging -> auth -> handler
 * }</pre>
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public final class Pipeline<I, O> implements Next<I, O> {

  private final Next<I, O> chain;

  private Pipeline(Next<I, O> chain) {
    this.chain = chain;
  }

  /**
   * Composes the middleware around the terminal handler.
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param middleware outermost first; copied, so later changes to the list have no effect
   * @param terminal the innermost handler
   * @return the pipeline
   */
  public static <I, O> Pipeline<I, O> of(List<? extends Middleware<I, O>> middleware, Next<I, O> terminal) {
    Objects.requireNonNull(terminal, "terminal");
    List<? extends Middleware<I, O>> copy = List.copyOf(middleware);
    Next<I, O> chain = terminal;
    for (int i = copy.size() - 1; i >= 0; i--) {
      Middleware<I, O> m = copy.get(i);
      Next<I, O> inner = chain;
      chain = input -> m.handle(input, inner);
    }
    return new Pipeline<>(chain);
  }

  /**
   * Runs the input through the middleware and the terminal handler.
   *
   * @param input the input
   * @return the output
   * @throws Exception whatever the chain throws and no middleware handled
   */
  @Override
  public O handle(I input) throws Exception {
    return chain.handle(input);
  }
}
