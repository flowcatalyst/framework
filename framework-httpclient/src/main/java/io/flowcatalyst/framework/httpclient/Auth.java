package io.flowcatalyst.framework.httpclient;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * How a {@link ServiceClient} authenticates, applied to every attempt (http-client contract,
 * section 4). Credentials are always read through a supplier, so a rotated value is picked up and
 * nothing is kept in a string field that might be printed.
 */
public sealed interface Auth {

  /**
   * No authentication.
   *
   * @return the strategy
   */
  static Auth none() {
    return new None();
  }

  /**
   * {@code Authorization: Bearer <token>}, the supplier asked on every attempt.
   *
   * @param token supplies the token, for example {@code platform::accessToken}
   * @return the strategy
   */
  static Auth bearer(Supplier<String> token) {
    return new Bearer(token, null);
  }

  /**
   * A bearer token; on a {@code 401} the callback runs (clear the cached token) and the request is
   * sent once more.
   *
   * @param token supplies the token
   * @param onUnauthorized runs after a 401, before the one repeat
   * @return the strategy
   */
  static Auth bearer(Supplier<String> token, Runnable onUnauthorized) {
    return new Bearer(token, onUnauthorized);
  }

  /**
   * {@code Authorization: Basic ...}.
   *
   * @param user the user, without {@code :}
   * @param password supplies the password
   * @return the strategy
   */
  static Auth basic(String user, Supplier<String> password) {
    if (user.indexOf(':') >= 0) {
      throw new IllegalArgumentException("a basic-auth user cannot contain ':'");
    }
    return new Basic(user, password);
  }

  /**
   * An API-key header.
   *
   * @param name the header name
   * @param value supplies the value
   * @return the strategy
   */
  static Auth header(String name, Supplier<String> value) {
    Headers.checkName(name);
    return new Header(name, value);
  }

  /**
   * OAuth 2 client credentials with a cached token, refreshed before it expires and after a 401.
   *
   * @param credentials the token endpoint and the client
   * @return the strategy
   */
  static Auth clientCredentials(ClientCredentials credentials) {
    return new OAuthClientCredentials(credentials);
  }

  /** No authentication. */
  record None() implements Auth {}

  /**
   * A bearer token.
   *
   * @param token supplies the token
   * @param onUnauthorized runs after a 401, or null to return the 401
   */
  record Bearer(Supplier<String> token, @Nullable Runnable onUnauthorized) implements Auth {
    @Override
    public String toString() {
      return "Bearer[****]";
    }
  }

  /**
   * Basic authentication.
   *
   * @param user the user
   * @param password supplies the password
   */
  record Basic(String user, Supplier<String> password) implements Auth {
    @Override
    public String toString() {
      return "Basic[" + user + ", ****]";
    }
  }

  /**
   * An API-key header.
   *
   * @param name the header name
   * @param value supplies the value
   */
  record Header(String name, Supplier<String> value) implements Auth {
    @Override
    public String toString() {
      return "Header[" + name + ", ****]";
    }
  }

  /**
   * OAuth 2 client credentials.
   *
   * @param credentials the token endpoint and the client
   */
  record OAuthClientCredentials(ClientCredentials credentials) implements Auth {}
}
