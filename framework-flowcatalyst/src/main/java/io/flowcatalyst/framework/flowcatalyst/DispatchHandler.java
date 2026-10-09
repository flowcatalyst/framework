package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.Context;

/**
 * Handles dispatch jobs of one event type (or a {@code *} family of types), registered with
 * {@link Receivers#onDispatch}.
 *
 * <p>A handler can run more than once for one job (a timeout after a commit, a lost response):
 * make it idempotent, for example with {@link DispatchDelivery#once}.
 */
@FunctionalInterface
public interface DispatchHandler {

  /**
   * Handles one delivery.
   *
   * @param ctx the client from {@code X-FlowCatalyst-Client} (or {@link Receivers#PLATFORM_CLIENT}
   *     for a platform-scoped job), this application, the job's correlation id (generated when the
   *     job has none) and the job id as execution id
   * @param delivery the job
   * @return what to tell the platform; never null
   * @throws Exception any failure; the platform is answered 503 and retries the job
   */
  DispatchResult handle(Context ctx, DispatchDelivery delivery) throws Exception;
}
