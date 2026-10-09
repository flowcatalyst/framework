package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.Context;

/**
 * Runs one scheduled job, registered with {@link Receivers#onScheduledJob}. It runs in the
 * background on a virtual thread, after the platform has been answered {@code 202}.
 *
 * <p>Unless the firing says {@code concurrent}, it runs only while this application holds the
 * overlap lock {@code scheduled-job:{jobCode}}. When it runs past its timeout, or the lock is lost,
 * its thread is interrupted: stop promptly when interrupted, and pass {@link
 * ScheduledJobFiring#fencingToken()} on to what you write if a stale run must be rejected there.
 */
@FunctionalInterface
public interface ScheduledJobHandler {

  /**
   * Runs the job.
   *
   * @param ctx {@link Receivers#PLATFORM_CLIENT} as the client, this application, the firing's
   *     correlation id (generated when it has none) and the instance id as execution id
   * @param firing the firing
   * @return the outcome; never null
   * @throws Exception any failure; reported as {@code FAILURE {error: message}} when the job
   *     tracks completion
   */
  JobResult run(Context ctx, ScheduledJobFiring firing) throws Exception;
}
