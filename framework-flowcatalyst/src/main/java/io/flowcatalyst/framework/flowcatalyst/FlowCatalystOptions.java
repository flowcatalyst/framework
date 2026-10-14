package io.flowcatalyst.framework.flowcatalyst;

import java.time.Duration;

/**
 * How the FlowCatalyst module behaves (flowcatalyst contract section 1; config-and-code contract
 * section 3.8). Given to {@link FlowCatalystModule#FlowCatalystModule(FlowCatalystOptions)} and
 * provided as a service, so other modules can read it. The platform's address, the application's
 * code, the client id and secret and the receiver secrets stay configuration.
 *
 * <pre>{@code
 * new FlowCatalystModule(FlowCatalystOptions.defaults()
 *     .withTimeout(Duration.ofSeconds(10))
 *     .withReceivers(ReceiverOptions.defaults().withPath("/hooks/flowcatalyst")))
 * }</pre>
 *
 * @param enabled whether the module talks to the platform at all; when false no platform setting
 *     is needed
 * @param timeout the timeout of requests to the platform; more than 0, at most 1 hour
 * @param outboxStaleAfter the {@code outbox} health check is down when the oldest undelivered row
 *     is older than this; more than 0
 * @param syncOnBoot whether start-up also syncs definitions (normally the deploy runs {@code sync})
 * @param receivers how the receivers behave
 */
public record FlowCatalystOptions(
    boolean enabled,
    Duration timeout,
    Duration outboxStaleAfter,
    boolean syncOnBoot,
    ReceiverOptions receivers) {

  /** Checks every value. */
  public FlowCatalystOptions {
    if (!timeout.isPositive() || timeout.compareTo(Duration.ofHours(1)) > 0) {
      throw new IllegalArgumentException("timeout must be more than 0 and at most 1h, was " + timeout);
    }
    if (!outboxStaleAfter.isPositive()) {
      throw new IllegalArgumentException("outboxStaleAfter must be more than 0, was " + outboxStaleAfter);
    }
  }

  /**
   * Enabled, a 30 second timeout, the outbox stale after 5 minutes, no sync on boot, {@link
   * ReceiverOptions#defaults()}.
   *
   * @return the defaults
   */
  public static FlowCatalystOptions defaults() {
    return new FlowCatalystOptions(true, Duration.ofSeconds(30), Duration.ofMinutes(5), false, ReceiverOptions.defaults());
  }

  /**
   * The defaults, with the module not talking to the platform (for local work without one).
   *
   * @return the options
   */
  public static FlowCatalystOptions disabled() {
    return defaults().withEnabled(false);
  }

  /**
   * A copy enabled or not.
   *
   * @param value whether the module talks to the platform
   * @return the copy
   */
  public FlowCatalystOptions withEnabled(boolean value) {
    return new FlowCatalystOptions(value, timeout, outboxStaleAfter, syncOnBoot, receivers);
  }

  /**
   * A copy with another platform timeout.
   *
   * @param value more than 0, at most 1 hour
   * @return the copy
   */
  public FlowCatalystOptions withTimeout(Duration value) {
    return new FlowCatalystOptions(enabled, value, outboxStaleAfter, syncOnBoot, receivers);
  }

  /**
   * A copy with another outbox staleness limit.
   *
   * @param value more than 0
   * @return the copy
   */
  public FlowCatalystOptions withOutboxStaleAfter(Duration value) {
    return new FlowCatalystOptions(enabled, timeout, value, syncOnBoot, receivers);
  }

  /**
   * A copy that syncs definitions at start-up or not.
   *
   * @param value whether start-up syncs
   * @return the copy
   */
  public FlowCatalystOptions withSyncOnBoot(boolean value) {
    return new FlowCatalystOptions(enabled, timeout, outboxStaleAfter, value, receivers);
  }

  /**
   * A copy with other receiver options.
   *
   * @param value the receivers' behaviour
   * @return the copy
   */
  public FlowCatalystOptions withReceivers(ReceiverOptions value) {
    return new FlowCatalystOptions(enabled, timeout, outboxStaleAfter, syncOnBoot, value);
  }
}
