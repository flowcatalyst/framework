package io.flowcatalyst.framework.auth;

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
 * @param deviceId the enrolled device ({@code device_id})
 * @param deviceMode whether the device is the worker's own or a shared kiosk
 * @param amr how the worker authenticated ({@code amr}), for example {@code pin}
 * @param authTime when the worker signed in ({@code auth_time}), or null when the token had none
 * @param sessionId the login family ({@code sid})
 * @param expiresAt when the access token expires
 */
public record FieldPrincipal(
    String workerId,
    @Nullable String workerCode,
    @Nullable String name,
    String clientId,
    String clientIdentifier,
    String appCode,
    String deviceId,
    DeviceMode deviceMode,
    List<String> amr,
    @Nullable Instant authTime,
    String sessionId,
    Instant expiresAt) {

  /** Whether the device belongs to one worker or is shared. */
  public enum DeviceMode {
    /** The worker's own device. */
    PERSONAL,
    /** A kiosk or hand-over device several workers use. */
    SHARED
  }

  /** Copies the list. */
  public FieldPrincipal {
    amr = List.copyOf(amr);
  }
}
