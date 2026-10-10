package io.flowcatalyst.framework.http.ratelimit;

import io.flowcatalyst.framework.http.Request;
import org.jspecify.annotations.Nullable;

/** Says whose budget a request uses. {@link KeyResolvers} has the usual ones. */
@FunctionalInterface
public interface KeyResolver {

  /**
   * The key.
   *
   * @param request the request
   * @return the key, or null when the request has none (it then uses one shared bucket)
   */
  @Nullable String key(Request request);
}
