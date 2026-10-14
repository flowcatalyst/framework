package io.flowcatalyst.framework.httpclient.module;

import io.flowcatalyst.framework.httpclient.ClientCredentials;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How a client named in {@link HttpClientModule} authenticates (config-and-code contract, section
 * 3.6). The strategy is code; its credentials are configuration, under {@code
 * http-clients.instances.<name>.auth}:
 *
 * <table>
 *   <caption>Credentials each strategy reads</caption>
 *   <tr><th>Strategy</th><th>Keys (all required)</th></tr>
 *   <tr><td>{@link #none()}</td><td>none</td></tr>
 *   <tr><td>{@link #bearer()}</td><td>{@code auth.token} (secret)</td></tr>
 *   <tr><td>{@link #basic()}</td><td>{@code auth.username}, {@code auth.password} (secret)</td></tr>
 *   <tr><td>{@link #header(String)}</td><td>{@code auth.value} (secret)</td></tr>
 *   <tr><td>{@link #clientCredentials()}</td><td>{@code auth.token-url}, {@code auth.client-id},
 *       {@code auth.client-secret} (secret)</td></tr>
 * </table>
 */
public sealed interface ClientAuth {

  /** No authentication. */
  record None() implements ClientAuth {}

  /** {@code Authorization: Bearer} with the configured token. */
  record Bearer() implements ClientAuth {}

  /** {@code Authorization: Basic} with the configured user name and password. */
  record Basic() implements ClientAuth {}

  /**
   * A header of this name carrying the configured secret value.
   *
   * @param name a valid header name, such as {@code X-Api-Key}
   */
  record Header(String name) implements ClientAuth {
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

    /** Checks the name. */
    public Header {
      if (!TOKEN.matcher(name).matches()) {
        throw new IllegalArgumentException("not a valid header name: '" + name + "'");
      }
    }
  }

  /**
   * OAuth 2 client credentials against the configured token URL, client id and secret.
   *
   * @param scope the {@code scope} parameter, or null for none
   * @param audience the {@code audience} parameter, or null for none
   * @param method how the client authenticates to the token endpoint
   */
  record ClientCredentialsGrant(
      @Nullable String scope, @Nullable String audience, ClientCredentials.Method method)
      implements ClientAuth {

    /** Checks the parameters. */
    public ClientCredentialsGrant {
      if (scope != null && scope.isBlank()) {
        throw new IllegalArgumentException("scope must not be blank");
      }
      if (audience != null && audience.isBlank()) {
        throw new IllegalArgumentException("audience must not be blank");
      }
    }

    /**
     * A copy with a scope.
     *
     * @param value the scope
     * @return the copy
     */
    public ClientCredentialsGrant withScope(String value) {
      return new ClientCredentialsGrant(value, audience, method);
    }

    /**
     * A copy with an audience.
     *
     * @param value the audience
     * @return the copy
     */
    public ClientCredentialsGrant withAudience(String value) {
      return new ClientCredentialsGrant(scope, value, method);
    }

    /**
     * A copy with another token-endpoint authentication method.
     *
     * @param value {@code POST} (the default) or {@code BASIC}
     * @return the copy
     */
    public ClientCredentialsGrant withMethod(ClientCredentials.Method value) {
      return new ClientCredentialsGrant(scope, audience, value);
    }
  }

  /**
   * No authentication (the default).
   *
   * @return the strategy
   */
  static ClientAuth none() {
    return new None();
  }

  /**
   * A bearer token from {@code auth.token}.
   *
   * @return the strategy
   */
  static ClientAuth bearer() {
    return new Bearer();
  }

  /**
   * Basic authentication from {@code auth.username} and {@code auth.password}.
   *
   * @return the strategy
   */
  static ClientAuth basic() {
    return new Basic();
  }

  /**
   * A header carrying {@code auth.value}.
   *
   * @param name the header name
   * @return the strategy
   */
  static ClientAuth header(String name) {
    return new Header(name);
  }

  /**
   * Client credentials from {@code auth.token-url}, {@code auth.client-id} and {@code
   * auth.client-secret}, with no scope or audience and the {@code POST} method.
   *
   * @return the strategy, to refine with {@code withScope}, {@code withAudience}, {@code
   *     withMethod}
   */
  static ClientCredentialsGrant clientCredentials() {
    return new ClientCredentialsGrant(null, null, ClientCredentials.Method.POST);
  }
}
