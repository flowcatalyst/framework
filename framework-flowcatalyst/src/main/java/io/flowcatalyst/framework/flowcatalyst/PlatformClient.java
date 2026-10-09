package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.sdk.FlowCatalystClient;
import io.flowcatalyst.sdk.error.FlowCatalystException;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The application's connection to the FlowCatalyst platform: a thin wrapper over the SDK client,
 * authenticated with OAuth2 client credentials (the application's service account).
 *
 * <p>Application code uses this wrapper; SDK types do not appear in its signatures. When
 * {@code flowcatalyst.enabled} is false the instance exists but {@link #isEnabled()} is false and
 * every other method throws {@link IllegalStateException}.
 */
public final class PlatformClient {

  private final @Nullable FlowCatalystClient sdk;
  private final @Nullable ApplicationCode application;

  private PlatformClient(@Nullable FlowCatalystClient sdk, @Nullable ApplicationCode application) {
    this.sdk = sdk;
    this.application = application;
  }

  static PlatformClient disabled() {
    return new PlatformClient(null, null);
  }

  static PlatformClient create(
      URI baseUrl,
      ApplicationCode application,
      String clientId,
      Secret clientSecret,
      Duration timeout,
      @Nullable URI subscriptionTargetBaseUrl) {
    FlowCatalystClient.Builder builder =
        FlowCatalystClient.builder()
            .baseUrl(baseUrl.toString())
            .clientCredentials(clientId, clientSecret.reveal())
            .timeout(timeout);
    if (subscriptionTargetBaseUrl != null) {
      builder.subscriptionTargetBaseUrl(subscriptionTargetBaseUrl.toString());
    }
    return new PlatformClient(builder.build(), application);
  }

  /**
   * Whether the module is enabled and this client can talk to the platform.
   *
   * @return false when {@code flowcatalyst.enabled} is false
   */
  public boolean isEnabled() {
    return sdk != null;
  }

  /**
   * The code of this application on the platform ({@code flowcatalyst.application}).
   *
   * @return the application code
   * @throws IllegalStateException when the module is disabled
   */
  public ApplicationCode application() {
    ApplicationCode code = application;
    if (code == null) {
      throw disabledException();
    }
    return code;
  }

  /**
   * Fetches a client-credentials token, or returns the cached one while it is valid. This is what
   * the {@code flowcatalyst} health check runs.
   *
   * @throws PlatformException when the platform refuses the credentials or cannot be reached
   * @throws IllegalStateException when the module is disabled
   */
  public void verifyCredentials() {
    accessToken();
  }

  /**
   * A bearer token for calls to the platform, cached by the SDK until shortly before it expires.
   *
   * @return the token; never log it
   * @throws PlatformException when the platform refuses the credentials or cannot be reached
   * @throws IllegalStateException when the module is disabled
   */
  public String accessToken() {
    try {
      return sdk().tokenManager().getToken();
    } catch (FlowCatalystException e) {
      String message = e.getMessage();
      throw new PlatformException(message == null ? "platform call failed" : message, e);
    }
  }

  /**
   * Discards the cached token, so the next {@link #accessToken()} fetches a new one. Used after
   * the platform answers 401.
   *
   * @throws IllegalStateException when the module is disabled
   */
  public void clearToken() {
    sdk().tokenManager().clearCache();
  }

  /** The SDK client, for use inside this module only. */
  FlowCatalystClient sdk() {
    FlowCatalystClient client = sdk;
    if (client == null) {
      throw disabledException();
    }
    return client;
  }

  private static IllegalStateException disabledException() {
    return new IllegalStateException("the flowcatalyst module is disabled (flowcatalyst.enabled)");
  }
}
