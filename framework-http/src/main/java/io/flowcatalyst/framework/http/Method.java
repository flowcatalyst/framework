package io.flowcatalyst.framework.http;

import org.jspecify.annotations.Nullable;

/** The HTTP methods the router handles. */
public enum Method {
  /** GET. */
  GET,
  /** HEAD: answered from GET with the body dropped, unless a HEAD route exists. */
  HEAD,
  /** POST. */
  POST,
  /** PUT. */
  PUT,
  /** PATCH. */
  PATCH,
  /** DELETE. */
  DELETE,
  /** OPTIONS. */
  OPTIONS;

  /**
   * Parses a method token (case-sensitive, as HTTP defines it).
   *
   * @param token the method as sent on the request line
   * @return the method, or null when it is not one the router handles
   */
  public static @Nullable Method parse(String token) {
    for (Method m : values()) {
      if (m.name().equals(token)) {
        return m;
      }
    }
    return null;
  }
}
