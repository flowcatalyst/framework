package io.flowcatalyst.framework.flowcatalyst;

import org.jspecify.annotations.Nullable;

/**
 * What a scheduled job reports when its firing tracks completion (flowcatalyst contract, section
 * 4): {@code SUCCESS} with an optional JSON result, or {@code FAILURE {error: message}}.
 *
 * <p>The result is serialised with Jackson (records, maps, lists, strings, numbers, {@code
 * JsonNode}); a result whose JSON is longer than {@value #MAX_RESULT_CHARS} characters is sent as
 * {@code {truncated: true, preview: <the first 10 000 characters>}}.
 */
public sealed interface JobResult {

  /** The longest result JSON sent as is. */
  int MAX_RESULT_CHARS = 10_000;

  /**
   * The job succeeded.
   *
   * @param value the result to store with the instance, or null for none
   */
  record Success(@Nullable Object value) implements JobResult {}

  /**
   * The job failed, without throwing.
   *
   * @param message why; stored with the instance as {@code {error: message}}
   */
  record Failure(String message) implements JobResult {}

  /**
   * Success with a result.
   *
   * @param value the result
   * @return {@link Success}
   */
  static JobResult of(Object value) {
    return new Success(value);
  }

  /**
   * Success without a result.
   *
   * @return {@link Success}
   */
  static JobResult success() {
    return new Success(null);
  }

  /**
   * A failure.
   *
   * @param message why
   * @return {@link Failure}
   */
  static JobResult failure(String message) {
    return new Failure(message);
  }
}
