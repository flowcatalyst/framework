package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.Actor;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A field worker on an enrolled device, as a verified field access token describes them
 * (sessions-and-login contract section 15.5). A field worker has no roles, applications, clients or
 * tier: no platform authority.
 *
 * @param workerId the worker's principal id ({@code sub}, {@code fwu_...})
 * @param workerCode the code the worker signs in with ({@code worker_code}), or null
 * @param name the display name, or null
 * @param clientId the client's platform id ({@code clt_...})
 * @param clientIdentifier the client's identifier, which becomes the {@code Context}'s client
 * @param appCode the application the token is for ({@code aud})
 * @param deviceId the enrolled device ({@code device_id}), or null on an unenrolled token (a key
 *     that is not an enrolled device; {@code device_id} is then absent)
 * @param deviceMode whether the device is the worker's own, a shared kiosk, or not enrolled
 * @param amr how the worker authenticated ({@code amr}), for example {@code pin}
 * @param authTime when the worker signed in ({@code auth_time}), or null when the token had none
 * @param sessionId the login family ({@code sid})
 * @param expiresAt when the access token expires
 * @param actor who is signed in as the worker ({@code act}: a supervisor, {@code field_worker}, or
 *     a platform user, {@code principal}), or null when the worker signed in as themselves; every
 *     other field is still the worker's
 * @param test whether this is a test identity ({@code test})
 * @param linkedPrincipalId the platform user this worker is linked to ({@code linked_principal_id},
 *     {@code prn_...}), or null when the worker is not linked (the claim is then absent). For audit
 *     correlation only: it grants nothing, and no permission, role or tier derives from it
 * @param keyThumbprint the RFC 7638 thumbprint of the device key the token is bound to
 *     ({@code cnf.jkt}), or {@code ""} when the token carries none; a push ticket bound to a key
 *     ({@code cnf}) is checked against it
 */
public record FieldPrincipal(
    String workerId,
    @Nullable String workerCode,
    @Nullable String name,
    String clientId,
    String clientIdentifier,
    String appCode,
    @Nullable String deviceId,
    DeviceMode deviceMode,
    List<String> amr,
    @Nullable Instant authTime,
    String sessionId,
    Instant expiresAt,
    @Nullable Actor actor,
    boolean test,
    @Nullable String linkedPrincipalId,
    String keyThumbprint) {

  /**
   * A worker who signed in as themselves and is not a test identity.
   *
   * @param workerId the worker's principal id
   * @param workerCode the worker code, or null
   * @param name the display name, or null
   * @param clientId the client's platform id
   * @param clientIdentifier the client's identifier
   * @param appCode the application
   * @param deviceId the enrolled device, or null when unenrolled
   * @param deviceMode the device mode
   * @param amr how the worker authenticated
   * @param authTime when the worker signed in, or null
   * @param sessionId the login family
   * @param expiresAt when the token expires
   */
  public FieldPrincipal(
      String workerId,
      @Nullable String workerCode,
      @Nullable String name,
      String clientId,
      String clientIdentifier,
      String appCode,
      @Nullable String deviceId,
      DeviceMode deviceMode,
      List<String> amr,
      @Nullable Instant authTime,
      String sessionId,
      Instant expiresAt) {
    this(workerId, workerCode, name, clientId, clientIdentifier, appCode, deviceId, deviceMode, amr, authTime, sessionId, expiresAt, null, false, null, "");
  }

  /**
   * A worker not linked to a platform user.
   *
   * @param workerId the worker's principal id
   * @param workerCode the worker code, or null
   * @param name the display name, or null
   * @param clientId the client's platform id
   * @param clientIdentifier the client's identifier
   * @param appCode the application
   * @param deviceId the enrolled device, or null when unenrolled
   * @param deviceMode the device mode
   * @param amr how the worker authenticated
   * @param authTime when the worker signed in, or null
   * @param sessionId the login family
   * @param expiresAt when the token expires
   * @param actor who is signed in as the worker, or null
   * @param test whether this is a test identity
   */
  public FieldPrincipal(
      String workerId,
      @Nullable String workerCode,
      @Nullable String name,
      String clientId,
      String clientIdentifier,
      String appCode,
      @Nullable String deviceId,
      DeviceMode deviceMode,
      List<String> amr,
      @Nullable Instant authTime,
      String sessionId,
      Instant expiresAt,
      @Nullable Actor actor,
      boolean test) {
    this(workerId, workerCode, name, clientId, clientIdentifier, appCode, deviceId, deviceMode, amr, authTime, sessionId, expiresAt, actor, test, null, "");
  }

  /**
   * A worker with the platform user it is linked to and no device key thumbprint (tokens that carry
   * no {@code cnf.jkt}).
   *
   * @param workerId the worker's principal id
   * @param workerCode the worker code, or null
   * @param name the display name, or null
   * @param clientId the client's platform id
   * @param clientIdentifier the client's identifier
   * @param appCode the application the token is for
   * @param deviceId the enrolled device, or null
   * @param deviceMode the device mode
   * @param amr how the worker authenticated
   * @param authTime when the worker signed in, or null
   * @param sessionId the login family
   * @param expiresAt when the access token expires
   * @param actor who is signed in as the worker, or null
   * @param test whether this is a test identity
   * @param linkedPrincipalId the linked platform user, or null
   */
  public FieldPrincipal(
      String workerId,
      @Nullable String workerCode,
      @Nullable String name,
      String clientId,
      String clientIdentifier,
      String appCode,
      @Nullable String deviceId,
      DeviceMode deviceMode,
      List<String> amr,
      @Nullable Instant authTime,
      String sessionId,
      Instant expiresAt,
      @Nullable Actor actor,
      boolean test,
      @Nullable String linkedPrincipalId) {
    this(workerId, workerCode, name, clientId, clientIdentifier, appCode, deviceId, deviceMode, amr, authTime, sessionId, expiresAt, actor, test, linkedPrincipalId, "");
  }

  /** Whether the device belongs to one worker or is shared. */
  public enum DeviceMode {
    /** The worker's own device. */
    PERSONAL,
    /** A kiosk or hand-over device several workers use. */
    SHARED,
    /** A key that is not an enrolled device: the token has no {@code device_id}. */
    UNENROLLED
  }

  /** Copies the list. */
  public FieldPrincipal {
    amr = List.copyOf(amr);
  }
}
