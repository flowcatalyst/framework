package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.sdk.FlowCatalystClient;
import io.flowcatalyst.sdk.error.FlowCatalystException;
import io.flowcatalyst.sdk.generated.model.CompleteInstanceRequest;
import io.flowcatalyst.sdk.generated.model.WriteInstanceLogRequest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

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
  private final @Nullable FlowCatalystClient reporting;
  private final @Nullable ApplicationCode application;

  /** The service principal, with the token it was read from (contract section 4b). */
  private record Resolved(String token, PrincipalId principal) {
    @Override
    public String toString() {
      return "Resolved[" + principal + "]"; // never the token
    }
  }

  private volatile @Nullable Resolved resolved;

  private PlatformClient(
      @Nullable FlowCatalystClient sdk,
      @Nullable FlowCatalystClient reporting,
      @Nullable ApplicationCode application) {
    this.sdk = sdk;
    this.reporting = reporting;
    this.application = application;
  }

  static PlatformClient disabled() {
    return new PlatformClient(null, null, null);
  }

  static PlatformClient create(
      URI baseUrl,
      ApplicationCode application,
      String clientId,
      Secret clientSecret,
      Duration timeout,
      @Nullable URI subscriptionTargetBaseUrl) {
    FlowCatalystClient main =
        builder(baseUrl, clientId, clientSecret, timeout, subscriptionTargetBaseUrl).build();
    // The SDK retries 408, 429, 502, 503 and 504 inside every call, with no per-call switch. The
    // scheduled-job reports have their own bounded retry (ScheduledJobRunner), so they use a
    // second client that makes exactly one HTTP call per attempt; otherwise 3 retries of ours
    // would multiply with 3 of the SDK's, up to 16 calls. It has its own token cache.
    FlowCatalystClient reporting =
        builder(baseUrl, clientId, clientSecret, timeout, subscriptionTargetBaseUrl)
            .retryAttempts(0)
            .build();
    return new PlatformClient(main, reporting, application);
  }

  private static FlowCatalystClient.Builder builder(
      URI baseUrl,
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
    return builder;
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
   * The application's service principal: the {@code sub} of the client-credentials token, which
   * must be a {@code SERVICE} token (flowcatalyst contract section 4b). The id is kept with the
   * token it was read from and read again only when the token changes (a refresh shortly before
   * it expires, or after {@link #clearToken()}), so this makes no request of its own beyond
   * {@link #accessToken()}. The receivers run every handler as this principal.
   *
   * @return the principal id, for example {@code prn_0HZXEQ5Y8JY5Z}
   * @throws PlatformException when no token can be fetched, or the token does not identify a
   *     service principal (not a JWT, no {@code sub}, {@code type} not {@code SERVICE})
   * @throws IllegalStateException when the module is disabled
   */
  public PrincipalId servicePrincipal() {
    String token = accessToken();
    Resolved last = resolved;
    if (last != null && last.token().equals(token)) {
      return last.principal();
    }
    PrincipalId principal = ServiceTokens.servicePrincipal(token);
    resolved = new Resolved(token, principal);
    return principal;
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

  /** How a scheduled-job instance ended, as reported with {@link #completeJobInstance}. */
  public enum Completion {
    /** The job succeeded. */
    SUCCESS,
    /** The job failed, timed out, was skipped or lost its overlap lock. */
    FAILURE
  }

  /**
   * Reports the end of a scheduled-job instance that tracks completion: {@code POST
   * /api/scheduled-jobs/instances/{instanceId}/complete} with {@code {status, result?}}. The
   * platform overwrites an earlier report for the same instance.
   *
   * @param instanceId the instance id from the firing
   * @param status how it ended
   * @param result a JSON result to store with it, or null for none
   * @throws PlatformException when the platform refuses the call or cannot be reached
   * @throws IllegalStateException when the module is disabled
   */
  public void completeJobInstance(String instanceId, Completion status, @Nullable JsonNode result) {
    CompleteInstanceRequest request = new CompleteInstanceRequest().status(status.name());
    if (result != null) {
      request.result(result);
    }
    try {
      reporting().scheduledJobs().completeInstance(instanceId, request);
    } catch (FlowCatalystException e) {
      throw failed(e);
    }
  }

  /**
   * Appends a log line to a scheduled-job instance: {@code POST
   * /api/scheduled-jobs/instances/{instanceId}/log}.
   *
   * @param instanceId the instance id from the firing
   * @param level the level
   * @param message the line
   * @throws PlatformException when the platform refuses the call or cannot be reached
   * @throws IllegalStateException when the module is disabled
   */
  public void logJobInstance(String instanceId, ScheduledJobFiring.LogLevel level, String message) {
    WriteInstanceLogRequest request =
        new WriteInstanceLogRequest().level(level.name()).message(message);
    try {
      reporting().scheduledJobs().logForInstance(instanceId, request);
    } catch (FlowCatalystException e) {
      throw failed(e);
    }
  }

  private static PlatformException failed(FlowCatalystException e) {
    String message = e.getMessage();
    PlatformException.Kind kind =
        switch (e.error()) {
          case io.flowcatalyst.sdk.error.SdkError.Forbidden f -> PlatformException.Kind.FORBIDDEN;
          case io.flowcatalyst.sdk.error.SdkError.NotFound n -> PlatformException.Kind.NOT_FOUND;
          default -> PlatformException.Kind.OTHER;
        };
    return new PlatformException(kind, message == null ? "platform call failed" : message, e);
  }

  // ---- reads for the admin pages (M4e): what an application service account may call ----------

  /**
   * One version of an event type's payload specification.
   *
   * @param version the version, for example {@code 1.0}
   * @param status {@code CURRENT}, {@code FINALISING} or {@code DEPRECATED}
   * @param schema the schema as pretty JSON text, or null when there is none
   */
  public record SpecVersion(String version, String status, @Nullable String schema) {}

  /**
   * An event type as the platform holds it.
   *
   * @param id the platform id
   * @param code {@code {application}:{subdomain}:{aggregate}:{event}}
   * @param name the display name
   * @param description what it means, or null
   * @param application the owning application's code
   * @param subdomain the subdomain
   * @param aggregate the aggregate
   * @param event the event name
   * @param status {@code CURRENT} or {@code ARCHIVED}
   * @param specVersions the payload specification versions
   */
  public record EventTypeInfo(
      String id,
      String code,
      String name,
      @Nullable String description,
      String application,
      String subdomain,
      String aggregate,
      String event,
      String status,
      List<SpecVersion> specVersions) {}

  /**
   * A role as the platform holds it.
   *
   * @param id the platform id
   * @param name the full name, {@code {application}:{role}}
   * @param application the owning application's code
   * @param displayName the display name
   * @param description what it is for, or null
   * @param source {@code CODE}, {@code DATABASE} or {@code SDK}
   * @param clientManaged whether it is managed at client scope
   * @param permissions the permissions it grants
   */
  public record RoleInfo(
      String id,
      String name,
      String application,
      String displayName,
      @Nullable String description,
      String source,
      boolean clientManaged,
      List<String> permissions) {}

  /**
   * Lists event types: {@code GET /api/event-types}. The platform lets an application service
   * account read the event types of its own application only; the filters narrow further.
   *
   * @param application the application code to filter by, or null
   * @param status {@code CURRENT} or {@code ARCHIVED}, or null for both
   * @param subdomain a subdomain, or null
   * @param aggregate an aggregate, or null
   * @return every match, in the platform's order (the platform does not page this list)
   * @throws PlatformException when the platform refuses or cannot be reached
   * @throws IllegalStateException when the module is disabled
   */
  public List<EventTypeInfo> eventTypes(
      @Nullable String application,
      @Nullable String status,
      @Nullable String subdomain,
      @Nullable String aggregate) {
    try {
      var response =
          sdk().eventTypes()
              .list(
                  new io.flowcatalyst.sdk.resources.EventTypesResource.Filters(
                      status, application, null, subdomain, aggregate));
      List<EventTypeInfo> out = new java.util.ArrayList<>();
      if (response != null && response.getItems() != null) {
        for (var item : response.getItems()) {
          out.add(eventType(item));
        }
      }
      return List.copyOf(out);
    } catch (FlowCatalystException e) {
      throw failed(e);
    }
  }

  /**
   * Reads one event type: {@code GET /api/event-types/{id}}.
   *
   * @param id the platform id
   * @return the event type, with its specification versions
   * @throws PlatformException (kind {@code NOT_FOUND} or {@code FORBIDDEN}) when the platform refuses
   * @throws IllegalStateException when the module is disabled
   */
  public EventTypeInfo eventType(String id) {
    try {
      return eventType(sdk().eventTypes().get(id));
    } catch (FlowCatalystException e) {
      throw failed(e);
    }
  }

  /**
   * Lists roles: {@code GET /api/roles}. The platform answers with every role it holds, not only
   * this application's; callers keep their own.
   *
   * @return every role the platform returned
   * @throws PlatformException when the platform refuses or cannot be reached
   * @throws IllegalStateException when the module is disabled
   */
  public List<RoleInfo> roles() {
    try {
      var response = sdk().roles().list();
      List<RoleInfo> out = new java.util.ArrayList<>();
      if (response != null && response.getRoles() != null) {
        for (var item : response.getRoles()) {
          out.add(role(item));
        }
      }
      return List.copyOf(out);
    } catch (FlowCatalystException e) {
      throw failed(e);
    }
  }

  /**
   * Reads one role: {@code GET /api/roles/{name}}.
   *
   * @param name the full role name, {@code {application}:{role}}
   * @return the role
   * @throws PlatformException (kind {@code NOT_FOUND} or {@code FORBIDDEN}) when the platform refuses
   * @throws IllegalStateException when the module is disabled
   */
  public RoleInfo role(String name) {
    try {
      return role(sdk().roles().get(name));
    } catch (FlowCatalystException e) {
      throw failed(e);
    }
  }

  private static EventTypeInfo eventType(io.flowcatalyst.sdk.generated.model.EventTypeResponse r) {
    List<SpecVersion> versions = new java.util.ArrayList<>();
    if (r.getSpecVersions() != null) {
      for (var v : r.getSpecVersions()) {
        versions.add(new SpecVersion(nz(v.getVersion()), nz(v.getStatus()), schemaText(v.getSchema())));
      }
    }
    return new EventTypeInfo(
        nz(r.getId()),
        nz(r.getCode()),
        nz(r.getName()),
        r.getDescription(),
        nz(r.getApplication()),
        nz(r.getSubdomain()),
        nz(r.getAggregate()),
        nz(r.getEventName()),
        nz(r.getStatus()),
        List.copyOf(versions));
  }

  private static RoleInfo role(io.flowcatalyst.sdk.generated.model.RoleResponse r) {
    return new RoleInfo(
        nz(r.getId()),
        nz(r.getName()),
        nz(r.getApplicationCode()),
        nz(r.getDisplayName()),
        r.getDescription(),
        nz(r.getSource()),
        Boolean.TRUE.equals(r.getClientManaged()),
        r.getPermissions() == null ? List.of() : List.copyOf(r.getPermissions()));
  }

  private static String nz(@Nullable String value) {
    return value == null ? "" : value;
  }

  private static @Nullable String schemaText(@Nullable Object schema) {
    if (schema == null) {
      return null;
    }
    try {
      return new tools.jackson.databind.json.JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
    } catch (RuntimeException e) {
      return schema.toString();
    }
  }

  /** The SDK client that never retries inside a call, for the module's own bounded retries. */
  private FlowCatalystClient reporting() {
    FlowCatalystClient client = reporting;
    if (client == null) {
      throw disabledException();
    }
    return client;
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
