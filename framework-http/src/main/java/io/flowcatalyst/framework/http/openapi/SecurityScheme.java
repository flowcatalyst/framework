package io.flowcatalyst.framework.http.openapi;

/**
 * How callers authenticate, contributed by the module that resolves the {@code Context} (OpenAPI
 * contract section 7) with {@link OpenApi#securityScheme}. Every operation that requires a context
 * lists every contributed scheme, each as its own alternative.
 */
public sealed interface SecurityScheme {

  /**
   * An API key in a cookie, such as the session cookie: {@code {"type": "apiKey", "in": "cookie",
   * "name": ...}}.
   *
   * @param name the cookie name
   */
  record CookieApiKey(String name) implements SecurityScheme {}

  /**
   * An API key in a header: {@code {"type": "apiKey", "in": "header", "name": ...}}.
   *
   * @param name the header name
   */
  record HeaderApiKey(String name) implements SecurityScheme {}

  /**
   * HTTP bearer: {@code {"type": "http", "scheme": "bearer", "bearerFormat": ...}}.
   *
   * @param format the token format, for example {@code JWT}
   */
  record Bearer(String format) implements SecurityScheme {}

  /** HTTP basic: {@code {"type": "http", "scheme": "basic"}}. */
  record Basic() implements SecurityScheme {}

  /**
   * The HTTP {@code DPoP} scheme (RFC 9449) carrying a FlowCatalyst field access token: {@code
   * {"type": "http", "scheme": "DPoP", "bearerFormat": "fc-field+jwt", "description": ...}} (OpenAPI
   * contract section 7, amended 2026-11-03).
   */
  record Dpop() implements SecurityScheme {}

  /**
   * A session cookie.
   *
   * @param name the cookie name
   * @return the scheme
   */
  static SecurityScheme cookie(String name) {
    return new CookieApiKey(name);
  }

  /**
   * An API key header.
   *
   * @param name the header name
   * @return the scheme
   */
  static SecurityScheme header(String name) {
    return new HeaderApiKey(name);
  }

  /**
   * Bearer tokens.
   *
   * @param format the token format, for example {@code JWT}
   * @return the scheme
   */
  static SecurityScheme bearer(String format) {
    return new Bearer(format);
  }

  /**
   * HTTP basic.
   *
   * @return the scheme
   */
  static SecurityScheme httpBasic() {
    return new Basic();
  }

  /**
   * DPoP-bound field access tokens ({@code Authorization: DPoP <token>} with a {@code DPoP} proof).
   *
   * @return the scheme
   */
  static SecurityScheme dpop() {
    return new Dpop();
  }
}
