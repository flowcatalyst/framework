package io.flowcatalyst.framework.flowcatalyst;

/**
 * How a scheduled-job handler is registered, for {@link Receivers#onScheduledJob(String, ScheduledJobHandler,
 * JobOptions)}.
 *
 * <p>By default a handler is exclusive: unless a firing says {@code concurrent}, it runs only while
 * this application holds the overlap lock, so start-up needs a lock instance. A handler registered
 * {@link #alwaysConcurrent()} declares that it is safe to run in overlap (or protects itself), and
 * needs no lock instance. Such a job must be defined on the platform with {@code concurrent} true:
 * a firing that asks for exclusion is not run, because this application has no way to give it.
 *
 * @param concurrentOnly true when the handler never takes the overlap lock
 */
public record JobOptions(boolean concurrentOnly) {

  /**
   * The default: exclusive unless the firing says {@code concurrent}; needs a lock instance.
   *
   * @return the options
   */
  public static JobOptions exclusive() {
    return new JobOptions(false);
  }

  /**
   * The handler may always run concurrently with itself; no lock instance is needed. A firing with
   * {@code concurrent=false} is refused and, when it tracks completion, reported {@code FAILURE
   * {skipped: true, reason: "not-registered-for-exclusive-run"}}.
   *
   * @return the options
   */
  public static JobOptions alwaysConcurrent() {
    return new JobOptions(true);
  }
}
