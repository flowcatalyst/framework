package io.flowcatalyst.framework.httpclient;

/** The HTTP methods the client sends. */
public enum RequestMethod {
  /** GET, idempotent. */
  GET(true),
  /** HEAD, idempotent; the response has no body. */
  HEAD(true),
  /** POST, not idempotent. */
  POST(false),
  /** PUT, idempotent. */
  PUT(true),
  /** PATCH, not idempotent. */
  PATCH(false),
  /** DELETE, idempotent. */
  DELETE(true),
  /** OPTIONS, idempotent. */
  OPTIONS(true);

  private final boolean idempotent;

  RequestMethod(boolean idempotent) {
    this.idempotent = idempotent;
  }

  /**
   * Whether the method is idempotent by definition, which makes a request retryable by default.
   *
   * @return true for GET, HEAD, PUT, DELETE and OPTIONS
   */
  public boolean idempotent() {
    return idempotent;
  }
}
