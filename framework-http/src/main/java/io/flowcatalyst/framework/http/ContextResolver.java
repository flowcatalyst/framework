package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.context.Context;

/**
 * Builds the {@link Context} for a request on a route declared with
 * {@link RouteOptions#requiresContext()}. A provided service, optional: start-up fails if a route
 * requires a context and none is provided.
 */
@FunctionalInterface
public interface ContextResolver {

  /**
   * Resolves the context. The framework replaces the returned context's correlation id with the
   * request's.
   *
   * @param request the request
   * @return the context, never null
   * @throws HttpException with status 401 and code {@code UNAUTHENTICATED} when it cannot
   * @throws Exception any other failure, which becomes a 500
   */
  Context resolve(Request request) throws Exception;
}
