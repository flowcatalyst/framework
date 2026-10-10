package io.flowcatalyst.framework.httpclient;

import org.jspecify.annotations.Nullable;

/**
 * A request did not produce a response, or its body could not be used. The message names the
 * service, method and path template, never a header value, a body, a token or a query string.
 */
public final class HttpClientException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** What went wrong. */
  public enum Kind {
    /** No connection could be made. */
    CONNECT,
    /** The exchange did not finish within the request timeout. */
    TIMEOUT,
    /** The response body was larger than the service's limit. */
    RESPONSE_TOO_LARGE,
    /** The exchange broke after the connection was made. */
    IO,
    /** The body is not the JSON that was asked for. */
    DECODE,
    /** The auth strategy could not get credentials (for example the token endpoint failed). */
    AUTH,
    /** The calling thread was interrupted; its interrupt flag is set again. */
    INTERRUPTED
  }

  private final Kind kind;

  /**
   * Creates the exception.
   *
   * @param kind what went wrong
   * @param message what was being done; never values, bodies or tokens
   * @param cause the underlying failure, or null
   */
  public HttpClientException(Kind kind, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  /**
   * What went wrong.
   *
   * @return the kind
   */
  public Kind kind() {
    return kind;
  }
}
