package io.flowcatalyst.framework.httpclient;

import org.jspecify.annotations.Nullable;

/** Told about every finished attempt; the framework adapter records metrics with it. */
@FunctionalInterface
public interface ClientObserver {

  /** Does nothing. */
  ClientObserver NONE = (service, method, pathTemplate, status, failure, nanos, attempt) -> {};

  /**
   * One attempt ended.
   *
   * @param service the service name
   * @param method the method
   * @param pathTemplate the path as written, with placeholders, never the filled path
   * @param status the response status, or 0 when there was no response
   * @param failure the failure kind when there was no response, else null
   * @param nanos how long the attempt took
   * @param attempt 1 for the first attempt
   */
  void attempted(
      String service,
      RequestMethod method,
      String pathTemplate,
      int status,
      HttpClientException.@Nullable Kind failure,
      long nanos,
      int attempt);
}
