package io.flowcatalyst.framework.flowcatalyst;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * How the receivers behave (flowcatalyst contract section 4; config-and-code contract section
 * 3.8). Given with {@link FlowCatalystOptions#withReceivers(ReceiverOptions)}; the signing secrets
 * and bearer tokens stay configuration.
 *
 * @param enabled whether {@code {path}/dispatch} and {@code {path}/scheduled-jobs} are mounted at
 *     all; with true, a receiver without a handler answers 404
 * @param path the routes' prefix: literal segments starting with {@code /}, no trailing {@code /}
 * @param requireSignature whether unsigned deliveries are refused (false accepts them; start-up
 *     warns)
 * @param dispatchTimeout how long a dispatch-job handler may run before it is interrupted and the
 *     platform answered 503; more than 0 (keep it below the job's timeout, platform default 30 s)
 * @param jobTimeout how long a scheduled job may run when its firing gives no timeout; more than 0
 * @param jobLockLease the lease of the scheduled-job overlap lock, refreshed every third of it; 1 s
 *     to 24 h
 * @param completionRetryDelay the wait before the first retry of a failed completion call, doubled
 *     for each of 3 retries; 0 to 1 minute
 * @param onceRetention how long {@code DispatchDelivery.once} keeps its records; more than 0
 * @param oncePurgeInterval how often old {@code fw_dispatch_once} rows are purged; more than 0
 */
public record ReceiverOptions(
    boolean enabled,
    String path,
    boolean requireSignature,
    Duration dispatchTimeout,
    Duration jobTimeout,
    Duration jobLockLease,
    Duration completionRetryDelay,
    Duration onceRetention,
    Duration oncePurgeInterval) {

  private static final Pattern PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)+");

  /** Checks every value. */
  public ReceiverOptions {
    if (!PATH.matcher(path).matches()) {
      throw new IllegalArgumentException(
          "path must be literal segments starting with '/' and without a trailing '/', was '" + path + "'");
    }
    positive("dispatchTimeout", dispatchTimeout);
    positive("jobTimeout", jobTimeout);
    if (jobLockLease.compareTo(Duration.ofSeconds(1)) < 0 || jobLockLease.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("jobLockLease must be 1s to 24h, was " + jobLockLease);
    }
    if (completionRetryDelay.isNegative() || completionRetryDelay.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("completionRetryDelay must be 0 to 1m, was " + completionRetryDelay);
    }
    positive("onceRetention", onceRetention);
    positive("oncePurgeInterval", oncePurgeInterval);
  }

  private static void positive(String field, Duration value) {
    if (!value.isPositive()) {
      throw new IllegalArgumentException(field + " must be more than 0, was " + value);
    }
  }

  /**
   * Mounted at {@code /fc}, signatures required, dispatch 25 s, jobs 10 min, lease 10 min,
   * completion retry 1 s, once-records kept 30 days and purged hourly.
   *
   * @return the defaults
   */
  public static ReceiverOptions defaults() {
    return new ReceiverOptions(
        true,
        "/fc",
        true,
        Duration.ofSeconds(25),
        Duration.ofMinutes(10),
        Duration.ofMinutes(10),
        Duration.ofSeconds(1),
        Duration.ofDays(30),
        Duration.ofHours(1));
  }

  /**
   * The defaults, with no receiver routes mounted.
   *
   * @return the options
   */
  public static ReceiverOptions disabled() {
    return defaults().withEnabled(false);
  }

  /**
   * A copy that mounts the routes or not.
   *
   * @param value whether the routes are mounted
   * @return the copy
   */
  public ReceiverOptions withEnabled(boolean value) {
    return new ReceiverOptions(
        value, path, requireSignature, dispatchTimeout, jobTimeout, jobLockLease, completionRetryDelay, onceRetention, oncePurgeInterval);
  }

  /**
   * A copy with another prefix.
   *
   * @param value literal segments, such as {@code /hooks/flowcatalyst}
   * @return the copy
   */
  public ReceiverOptions withPath(String value) {
    return new ReceiverOptions(
        enabled, value, requireSignature, dispatchTimeout, jobTimeout, jobLockLease, completionRetryDelay, onceRetention, oncePurgeInterval);
  }

  /**
   * A copy that requires signatures or not. False lets anyone who can reach the routes run your
   * handlers; for local work only.
   *
   * @param value whether unsigned deliveries are refused
   * @return the copy
   */
  public ReceiverOptions withRequireSignature(boolean value) {
    return new ReceiverOptions(
        enabled, path, value, dispatchTimeout, jobTimeout, jobLockLease, completionRetryDelay, onceRetention, oncePurgeInterval);
  }

  /**
   * A copy with another dispatch-job handler timeout.
   *
   * @param value more than 0
   * @return the copy
   */
  public ReceiverOptions withDispatchTimeout(Duration value) {
    return new ReceiverOptions(
        enabled, path, requireSignature, value, jobTimeout, jobLockLease, completionRetryDelay, onceRetention, oncePurgeInterval);
  }

  /**
   * A copy with another default scheduled-job timeout.
   *
   * @param value more than 0
   * @return the copy
   */
  public ReceiverOptions withJobTimeout(Duration value) {
    return new ReceiverOptions(
        enabled, path, requireSignature, dispatchTimeout, value, jobLockLease, completionRetryDelay, onceRetention, oncePurgeInterval);
  }

  /**
   * A copy with another overlap-lock lease.
   *
   * @param value 1 s to 24 h
   * @return the copy
   */
  public ReceiverOptions withJobLockLease(Duration value) {
    return new ReceiverOptions(
        enabled, path, requireSignature, dispatchTimeout, jobTimeout, value, completionRetryDelay, onceRetention, oncePurgeInterval);
  }

  /**
   * A copy with another first completion-retry wait.
   *
   * @param value 0 to 1 minute
   * @return the copy
   */
  public ReceiverOptions withCompletionRetryDelay(Duration value) {
    return new ReceiverOptions(
        enabled, path, requireSignature, dispatchTimeout, jobTimeout, jobLockLease, value, onceRetention, oncePurgeInterval);
  }

  /**
   * A copy with another retention of once-records.
   *
   * @param value more than 0; longer than the platform can redeliver a job
   * @return the copy
   */
  public ReceiverOptions withOnceRetention(Duration value) {
    return new ReceiverOptions(
        enabled, path, requireSignature, dispatchTimeout, jobTimeout, jobLockLease, completionRetryDelay, value, oncePurgeInterval);
  }

  /**
   * A copy with another purge interval of once-records.
   *
   * @param value more than 0
   * @return the copy
   */
  public ReceiverOptions withOncePurgeInterval(Duration value) {
    return new ReceiverOptions(
        enabled, path, requireSignature, dispatchTimeout, jobTimeout, jobLockLease, completionRetryDelay, onceRetention, value);
  }
}
