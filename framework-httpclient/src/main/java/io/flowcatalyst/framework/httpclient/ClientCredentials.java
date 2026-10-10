package io.flowcatalyst.framework.httpclient;

import java.net.URI;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * An OAuth 2 client-credentials grant (http-client contract, section 4).
 *
 * @param tokenUrl the token endpoint: https, or http to a loopback address
 * @param clientId the client id
 * @param clientSecret supplies the client secret
 * @param scope the scope, or null
 * @param audience the audience, or null
 * @param method how the client authenticates to the token endpoint
 */
public record ClientCredentials(
    URI tokenUrl,
    String clientId,
    Supplier<String> clientSecret,
    @Nullable String scope,
    @Nullable String audience,
    Method method) {

  private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

  /** How the client id and secret travel to the token endpoint. */
  public enum Method {
    /** In the form body (the FlowCatalyst platform's way; the default). */
    POST,
    /** In an {@code Authorization: Basic} header. */
    BASIC
  }

  /** Checks the token URL. */
  public ClientCredentials {
    String scheme = tokenUrl.getScheme();
    String host = tokenUrl.getHost();
    boolean https = "https".equalsIgnoreCase(scheme);
    boolean loopbackHttp = "http".equalsIgnoreCase(scheme) && host != null && LOOPBACK.contains(host);
    if (!tokenUrl.isAbsolute() || host == null || tokenUrl.getRawUserInfo() != null || !(https || loopbackHttp)) {
      throw new IllegalArgumentException(
          "the token URL must be https (or http to localhost), with a host and no user info");
    }
    if (clientId.isBlank()) {
      throw new IllegalArgumentException("clientId must not be blank");
    }
  }

  /**
   * Starts a grant.
   *
   * @param tokenUrl the token endpoint
   * @param clientId the client id
   * @param clientSecret supplies the client secret
   * @return a builder
   */
  public static Builder builder(URI tokenUrl, String clientId, Supplier<String> clientSecret) {
    return new Builder(tokenUrl, clientId, clientSecret);
  }

  @Override
  public String toString() {
    return "ClientCredentials[" + tokenUrl.getHost() + ", " + clientId + ", ****]";
  }

  /** Builds {@link ClientCredentials}. */
  public static final class Builder {
    private final URI tokenUrl;
    private final String clientId;
    private final Supplier<String> clientSecret;
    private @Nullable String scope;
    private @Nullable String audience;
    private Method method = Method.POST;

    private Builder(URI tokenUrl, String clientId, Supplier<String> clientSecret) {
      this.tokenUrl = tokenUrl;
      this.clientId = clientId;
      this.clientSecret = clientSecret;
    }

    /**
     * Sets the scope.
     *
     * @param value the scope
     * @return this builder
     */
    public Builder scope(String value) {
      this.scope = value;
      return this;
    }

    /**
     * Sets the audience.
     *
     * @param value the audience
     * @return this builder
     */
    public Builder audience(String value) {
      this.audience = value;
      return this;
    }

    /**
     * Sets how the client authenticates to the token endpoint.
     *
     * @param value the method
     * @return this builder
     */
    public Builder method(Method value) {
      this.method = value;
      return this;
    }

    /**
     * Builds the grant.
     *
     * @return the grant
     */
    public ClientCredentials build() {
      return new ClientCredentials(tokenUrl, clientId, clientSecret, scope, audience, method);
    }
  }
}
