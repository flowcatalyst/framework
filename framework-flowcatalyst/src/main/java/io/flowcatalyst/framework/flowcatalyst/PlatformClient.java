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
  private final @Nullable PlatformHttp http;

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
      @Nullable ApplicationCode application,
      @Nullable PlatformHttp http) {
    this.sdk = sdk;
    this.reporting = reporting;
    this.application = application;
    this.http = http;
  }

  static PlatformClient disabled() {
    return new PlatformClient(null, null, null, null);
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
    // The routes the pinned SDK has no methods for (users, invitations, events, dispatch jobs: the
    // admin pages) go over plain HTTP with the same service-account token (interim, admin contract 12).
    PlatformHttp http = new PlatformHttp(baseUrl, timeout, () -> tokenOf(main), () -> main.tokenManager().clearCache());
    return new PlatformClient(main, reporting, application, http);
  }

  private static String tokenOf(FlowCatalystClient client) {
    try {
      return client.tokenManager().getToken();
    } catch (FlowCatalystException e) {
      String message = e.getMessage();
      throw new PlatformException(message == null ? "platform call failed" : message, e);
    }
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

  // ---- users, invitations, events and dispatch jobs for the admin pages (M4e slices 1 and 4) -------
  //
  // The pinned SDK (0.0.11) has no methods for these routes (the platform's F20 added them to a later,
  // unreleased SDK), so they go over plain HTTP with the service account's token. Every method below
  // is for THIS application: the application code in a path is always the configured one.

  /**
   * Lists the application's users: {@code GET /api/applications/{app}/users}.
   *
   * @param query filters and page
   * @return the page
   * @throws PlatformException when the platform refuses or cannot be reached
   * @throws IllegalStateException when the module is disabled
   */
  public PlatformAdminData.UserPage users(PlatformAdminData.UserQuery query) {
    java.util.Map<String, @Nullable String> q = new java.util.LinkedHashMap<>();
    q.put("q", query.q());
    q.put("active", query.active() == null ? null : String.valueOf(query.active()));
    q.put("clientId", query.clientId());
    q.put("roles", query.roles().isEmpty() ? null : String.join(",", query.roles()));
    q.put("page", String.valueOf(query.page()));
    q.put("pageSize", String.valueOf(query.pageSize()));
    q.put("sortField", query.sortField());
    q.put("sortOrder", query.sortOrder());
    return PlatformAdminParsing.users(http().call("GET", appPath() + "/users", q, null));
  }

  /**
   * Reads one of the application's users: {@code GET /api/applications/{app}/users/{id}}.
   *
   * @param id the user's id
   * @return the user
   * @throws PlatformException (kind {@code NOT_FOUND}) when the platform has no such user of the application
   */
  public PlatformAdminData.UserInfo user(String id) {
    return PlatformAdminParsing.user(http().call("GET", appPath() + "/users/" + PlatformHttp.enc(id), java.util.Map.of(), null));
  }

  /**
   * Invites a user holding the application's roles: {@code POST /api/applications/{app}/users}. The
   * result may hold the invitation link: hand it to the person who asked and to nobody else.
   *
   * @param request the invitation
   * @return the new user's id and, when asked for, the link
   * @throws PlatformException (kind {@code REJECTED} with the platform's code and message, for example
   *     {@code EMAIL_EXISTS} or {@code ROLE_APP_FORBIDDEN}) when the platform says no
   */
  public PlatformAdminData.InviteResult invite(PlatformAdminData.InviteRequest request) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("email", request.email());
    body.put("name", request.name());
    body.put("clientId", request.clientId());
    body.put("roles", request.roles());
    body.put("sendInvitation", request.sendInvitation());
    body.put("returnInviteLink", request.returnInviteLink());
    return PlatformAdminParsing.invite(http().call("POST", appPath() + "/users", java.util.Map.of(), body));
  }

  /**
   * Lists the application's outstanding invitations: {@code GET /api/applications/{app}/invitations}.
   * The platform never sends a link here.
   *
   * @param status {@code PENDING} or {@code EXPIRED}, or null for both
   * @param page the 0-based page
   * @param pageSize the page size
   * @return the page
   */
  public PlatformAdminData.InvitationPage invitations(@Nullable String status, int page, int pageSize) {
    java.util.Map<String, @Nullable String> q = new java.util.LinkedHashMap<>();
    q.put("status", status);
    q.put("page", String.valueOf(page));
    q.put("pageSize", String.valueOf(pageSize));
    return PlatformAdminParsing.invitations(http().call("GET", appPath() + "/invitations", q, null));
  }

  /**
   * Resends an invitation: {@code POST /api/applications/{app}/invitations/{id}/resend}. The earlier
   * link stops working. The result may hold the link: hand it to the person who asked and to nobody else.
   *
   * @param userId the invited user's id
   * @param sendInvitation whether the platform emails the invitation
   * @param returnInviteLink whether the platform returns the link instead
   * @return the expiry and, when asked for, the link
   */
  public PlatformAdminData.ResendResult resendInvitation(String userId, boolean sendInvitation, boolean returnInviteLink) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("sendInvitation", sendInvitation);
    body.put("returnInviteLink", returnInviteLink);
    return PlatformAdminParsing.resend(
        http().call("POST", appPath() + "/invitations/" + PlatformHttp.enc(userId) + "/resend", java.util.Map.of(), body));
  }

  /**
   * Revokes an invitation: {@code DELETE /api/applications/{app}/invitations/{id}}. The platform
   * deactivates the user and deletes the invite links.
   *
   * @param userId the invited user's id
   */
  public void revokeInvitation(String userId) {
    http().call("DELETE", appPath() + "/invitations/" + PlatformHttp.enc(userId), java.util.Map.of(), null);
  }

  /**
   * Lists events, newest first: {@code GET /api/events}. The platform confines an application
   * service account to its own application's events; {@code applications=} is sent as well.
   *
   * @param query filters and page
   * @return the events (the platform answers a bare array)
   */
  public List<PlatformAdminData.EventInfo> events(PlatformAdminData.EventQuery query) {
    java.util.Map<String, @Nullable String> q = new java.util.LinkedHashMap<>();
    q.put("applications", application().value());
    q.put("type", query.type());
    q.put("subject", query.subject());
    q.put("correlationId", query.correlationId());
    q.put("clientId", query.clientId());
    q.put("since", query.since());
    q.put("until", query.until());
    q.put("limit", String.valueOf(query.limit()));
    q.put("offset", String.valueOf(query.offset()));
    return PlatformAdminParsing.events(http().call("GET", "/api/events", q, null));
  }

  /**
   * Reads one event with its payload: {@code GET /api/events/{id}}.
   *
   * @param id the event id
   * @return the event
   * @throws PlatformException (kind {@code NOT_FOUND}) when there is no such event of the application
   */
  public PlatformAdminData.EventDetail event(String id) {
    return PlatformAdminParsing.eventDetail(http().call("GET", "/api/events/" + PlatformHttp.enc(id), java.util.Map.of(), null));
  }

  /**
   * Lists dispatch jobs, newest first: {@code GET /api/dispatch-jobs}.
   *
   * @param query filters and page
   * @return the jobs (the platform answers a bare array)
   */
  public List<PlatformAdminData.JobInfo> dispatchJobs(PlatformAdminData.JobQuery query) {
    java.util.Map<String, @Nullable String> q = new java.util.LinkedHashMap<>();
    q.put("applications", application().value());
    q.put("statuses", query.statuses().isEmpty() ? null : String.join(",", query.statuses()));
    q.put("code", query.code());
    q.put("clientId", query.clientId());
    q.put("messageGroup", query.messageGroup());
    q.put("since", query.since());
    q.put("until", query.until());
    q.put("limit", String.valueOf(query.limit()));
    q.put("offset", String.valueOf(query.offset()));
    return PlatformAdminParsing.jobs(http().call("GET", "/api/dispatch-jobs", q, null));
  }

  /**
   * Reads one dispatch job: {@code GET /api/dispatch-jobs/{id}}.
   *
   * @param id the job id
   * @return the job
   * @throws PlatformException (kind {@code NOT_FOUND}) when there is no such job of the application
   */
  public PlatformAdminData.JobInfo dispatchJob(String id) {
    return PlatformAdminParsing.job(http().call("GET", "/api/dispatch-jobs/" + PlatformHttp.enc(id), java.util.Map.of(), null));
  }

  /**
   * Reads a dispatch job's delivery attempts: {@code GET /api/dispatch-jobs/{id}/attempts}.
   *
   * @param id the job id
   * @return the attempts, oldest first as the platform sends them
   */
  public List<PlatformAdminData.AttemptInfo> dispatchJobAttempts(String id) {
    return PlatformAdminParsing.attempts(http().call("GET", "/api/dispatch-jobs/" + PlatformHttp.enc(id) + "/attempts", java.util.Map.of(), null));
  }

  /**
   * Lists the dispatch jobs an event raised: {@code GET /api/dispatch-jobs/event/{eventId}}.
   *
   * @param eventId the event id
   * @return the jobs
   */
  public List<PlatformAdminData.JobInfo> dispatchJobsOfEvent(String eventId) {
    return PlatformAdminParsing.jobs(http().call("GET", "/api/dispatch-jobs/event/" + PlatformHttp.enc(eventId), java.util.Map.of(), null));
  }

  // ---- platform configuration (platform-config contract section 2) -------------------------------

  /**
   * Lists the application's configuration: {@code GET /api/platform-config/{app}}. The platform
   * answers {@code ***} for every secret.
   *
   * @return the properties, global and per client
   * @throws PlatformException when the platform refuses or cannot be reached
   */
  public List<PlatformConfigData.Property> configProperties() {
    return PlatformAdminParsing.configProperties(
        http().call("GET", "/api/platform-config/" + PlatformHttp.enc(application().value()), java.util.Map.of(), null));
  }

  /**
   * Sets a property: {@code PUT /api/config/{app}/{section}/{property}?clientId=}.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id or identifier, or null for the global value
   * @param request the value, its type and description
   * @return the property as stored; a secret's value is {@code ***}
   * @throws PlatformException when the platform refuses or cannot be reached
   */
  public PlatformConfigData.Property setConfigProperty(
      String section, String property, @Nullable String clientId, PlatformConfigData.SetRequest request) {
    return setConfigProperty(section, property, clientId, request, null);
  }

  /**
   * Sets a property on behalf of someone: as {@link #setConfigProperty(String, String, String,
   * PlatformConfigData.SetRequest)}, sending {@code X-On-Behalf-Of} so the platform's audit row names
   * the signed-in person.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id or identifier, or null for the global value
   * @param request the value, its type and description
   * @param onBehalfOf who the change is for (an id or email, never a token), or null; cleaned to
   *     printable ASCII, trimmed, at most 256 characters
   * @return the property as stored; a secret's value is {@code ***}
   * @throws PlatformException when the platform refuses or cannot be reached
   */
  public PlatformConfigData.Property setConfigProperty(
      String section, String property, @Nullable String clientId, PlatformConfigData.SetRequest request, @Nullable String onBehalfOf) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("value", request.value());
    body.put("valueType", request.valueType());
    if (request.description() != null) {
      body.put("description", request.description());
    }
    PlatformConfigData.Property stored =
        PlatformAdminParsing.configProperty(
            http().call("PUT", configPath(section, property), clientQuery(clientId), body, behalfHeaders(onBehalfOf)));
    learnClient(clientId, stored.clientId());
    return stored;
  }

  /**
   * Deletes a property: {@code DELETE /api/config/{app}/{section}/{property}?clientId=}. The platform
   * answers {@code 204} whether or not it existed.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id or identifier, or null for the global value
   * @throws PlatformException when the platform refuses or cannot be reached
   */
  public void deleteConfigProperty(String section, String property, @Nullable String clientId) {
    deleteConfigProperty(section, property, clientId, null);
  }

  /**
   * Deletes a property on behalf of someone (see {@link #setConfigProperty(String, String, String,
   * PlatformConfigData.SetRequest, String)}).
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id or identifier, or null for the global value
   * @param onBehalfOf who the change is for, or null
   * @throws PlatformException when the platform refuses or cannot be reached
   */
  public void deleteConfigProperty(String section, String property, @Nullable String clientId, @Nullable String onBehalfOf) {
    http().call("DELETE", configPath(section, property), clientQuery(clientId), null, behalfHeaders(onBehalfOf));
  }

  /**
   * Reads a secret's plaintext: {@code GET /api/config/{app}/{section}/{property}/secret?clientId=}.
   * The platform audits it. A failure never carries the value.
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id or identifier, or null for the global value
   * @return the plaintext; hand it to the caller and to nobody else, and never log it
   * @throws PlatformException (kind {@code NOT_FOUND} for no such property, {@code REJECTED} with code
   *     {@code NOT_A_SECRET} for a plain one, {@code FORBIDDEN} when not allowed)
   */
  public PlatformConfigData.Revealed revealConfigSecret(String section, String property, @Nullable String clientId) {
    return revealConfigSecret(section, property, clientId, null);
  }

  /**
   * Reads a secret's plaintext on behalf of someone (see {@link #setConfigProperty(String, String,
   * String, PlatformConfigData.SetRequest, String)}).
   *
   * @param section the section
   * @param property the property
   * @param clientId the platform client id or identifier, or null for the global value
   * @param onBehalfOf who the read is for, or null
   * @return the plaintext; hand it to the caller and to nobody else, and never log it
   * @throws PlatformException as for the overload without {@code onBehalfOf}
   */
  public PlatformConfigData.Revealed revealConfigSecret(
      String section, String property, @Nullable String clientId, @Nullable String onBehalfOf) {
    PlatformConfigData.Revealed revealed =
        PlatformAdminParsing.revealed(
            http().call("GET", configPath(section, property) + "/secret", clientQuery(clientId), null, behalfHeaders(onBehalfOf)));
    learnClient(clientId, revealed.clientId());
    return revealed;
  }

  /** The longest {@code X-On-Behalf-Of} value the platform accepts. */
  static final int ON_BEHALF_OF_MAX = 256;

  /**
   * The text for the {@code X-On-Behalf-Of} header: printable ASCII only (control and non-ASCII
   * characters removed), trimmed, at most 256 characters; null when nothing is left.
   */
  static @Nullable String onBehalfOfHeader(@Nullable String who) {
    if (who == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(Math.min(who.length(), ON_BEHALF_OF_MAX));
    for (int i = 0; i < who.length(); i++) {
      char c = who.charAt(i);
      if (c >= 0x20 && c <= 0x7E) {
        out.append(c);
      }
    }
    String text = out.toString().strip();
    if (text.length() > ON_BEHALF_OF_MAX) {
      text = text.substring(0, ON_BEHALF_OF_MAX).stripTrailing();
    }
    return text.isEmpty() ? null : text;
  }

  private static java.util.Map<String, String> behalfHeaders(@Nullable String onBehalfOf) {
    String text = onBehalfOfHeader(onBehalfOf);
    return text == null ? java.util.Map.of() : java.util.Map.of("X-On-Behalf-Of", text);
  }

  /** Most identifier-to-id pairs remembered; a client count beyond this just stops being aliased. */
  private static final int MAX_ALIASES = 10_000;

  private static final java.util.regex.Pattern ALIAS_VALUE = java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,100}");

  private final java.util.concurrent.ConcurrentHashMap<String, String> clientAliases = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * The stored client id for a client reference, as far as this process has learned it: the platform
   * answers a set or a reveal that named a client by identifier with the stored id ({@code clt_...}),
   * and this remembers the pair. A reference that is a stored id, or an identifier not yet seen, is
   * returned unchanged.
   *
   * @param client a client id or identifier
   * @return the stored id when known, else {@code client}
   */
  public String canonicalClient(String client) {
    return clientAliases.getOrDefault(client, client);
  }

  /** Remembers that the platform stores {@code requested} as {@code stored}. */
  private void learnClient(@Nullable String requested, @Nullable String stored) {
    if (requested == null || stored == null || requested.equals(stored) || !ALIAS_VALUE.matcher(stored).matches()) {
      return;
    }
    if (clientAliases.size() < MAX_ALIASES || clientAliases.containsKey(requested)) {
      clientAliases.put(requested, stored);
    }
  }

  private String configPath(String section, String property) {
    return "/api/config/" + PlatformHttp.enc(application().value()) + "/" + PlatformHttp.enc(section) + "/" + PlatformHttp.enc(property);
  }

  private static java.util.Map<String, @Nullable String> clientQuery(@Nullable String clientId) {
    java.util.Map<String, @Nullable String> q = new java.util.LinkedHashMap<>();
    q.put("clientId", clientId);
    return q;
  }

  private String appPath() {
    return "/api/applications/" + PlatformHttp.enc(application().value());
  }

  private PlatformHttp http() {
    PlatformHttp h = http;
    if (h == null) {
      throw disabledException();
    }
    return h;
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
