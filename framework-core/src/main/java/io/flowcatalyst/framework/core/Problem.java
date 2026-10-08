package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.config.ConfigProblem;
import org.jspecify.annotations.Nullable;

/**
 * One start-up problem.
 *
 * @param module the module concerned, when known
 * @param key the config key or other subject, when there is one
 * @param source where an offending value came from, when it has one
 * @param message what is wrong; never contains the raw text of a secret
 * @param cause the underlying exception, when there is one
 */
public record Problem(
    @Nullable String module,
    @Nullable String key,
    @Nullable String source,
    String message,
    @Nullable Throwable cause) {

  /**
   * A problem without a cause.
   *
   * @param module the module concerned, when known
   * @param key the subject, when there is one
   * @param message what is wrong
   * @return the problem
   */
  public static Problem of(@Nullable String module, @Nullable String key, String message) {
    return new Problem(module, key, null, message, null);
  }

  static Problem fromConfig(ConfigProblem p, @Nullable String fallbackModule) {
    String module = p.module() != null ? p.module() : fallbackModule;
    return new Problem(module, p.key(), p.source(), p.message(), null);
  }

  @Override
  public String toString() {
    return new ConfigProblem(module, key, source, message).toString();
  }
}
