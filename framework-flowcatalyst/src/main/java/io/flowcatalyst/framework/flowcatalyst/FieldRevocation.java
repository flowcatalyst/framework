package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.Context;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A field-identity revocation the platform emitted (sessions-and-login contract section 15.7), built
 * from the event data as the platform writes it. These are for <b>application state</b> (cached work
 * assignments, open gate sessions), not for token validity: tokens expire on their own within ten
 * minutes, and {@link RequireLiveField} is the check for an action that cannot wait.
 *
 * <pre>{@code
 * Receivers receivers = app.get(Receivers.class);
 * receivers.onFieldRevocation((ctx, event) -> {
 *   switch (event) {
 *     case FieldRevocation.WorkerSuspended w -> assignments.dropFor(w.workerId());
 *     case FieldRevocation.DeviceRevoked d -> gates.closeFor(d.deviceId());
 *     case FieldRevocation.SessionRevoked s -> gates.closeSession(s.sessionId());
 *   }
 * });
 * }</pre>
 */
public sealed interface FieldRevocation {

  /** The event type of {@link WorkerSuspended}. */
  String WORKER_SUSPENDED = "platform:iam:field-worker:suspended";

  /** The event type of {@link DeviceRevoked}. */
  String DEVICE_REVOKED = "platform:iam:field-device:revoked";

  /** The event type of {@link SessionRevoked}. */
  String SESSION_REVOKED = "platform:iam:field-session:revoked";

  /** The three event types, in the order above. */
  List<String> EVENT_TYPES = List.of(WORKER_SUSPENDED, DEVICE_REVOKED, SESSION_REVOKED);

  /**
   * A worker was suspended; all their sessions were revoked.
   *
   * @param workerId the worker's principal id ({@code fwu_...})
   * @param clientId the client's platform id
   */
  record WorkerSuspended(String workerId, @Nullable String clientId) implements FieldRevocation {}

  /**
   * A device was revoked (terminal); all its sessions were revoked.
   *
   * @param deviceId the device
   * @param clientId the client's platform id
   */
  record DeviceRevoked(String deviceId, @Nullable String clientId) implements FieldRevocation {}

  /**
   * Sessions were revoked by an administrator, by a worker or device suspension, or because a
   * rotated refresh token was presented again (a stolen token). Routine rotation is not announced.
   * The platform sets the login family ({@code familyId}, the {@code sid} of the tokens) only when it
   * revokes one family (a refresh-token reuse); revoking all sessions of a worker or a device gives
   * {@code workerId} or {@code deviceId} and no family.
   *
   * @param sessionId the login family ({@code sid} of its access tokens), when the event names one
   * @param workerId the worker whose sessions were revoked, when the event names one
   * @param deviceId the device whose sessions were revoked, when the event names one
   * @param clientId the client's platform id
   * @param reason why the sessions were revoked
   */
  record SessionRevoked(
      @Nullable String sessionId, @Nullable String workerId, @Nullable String deviceId, @Nullable String clientId, @Nullable String reason)
      implements FieldRevocation {}

  /**
   * Builds the value from a delivery's event data.
   *
   * @param eventType one of {@link #EVENT_TYPES}
   * @param data the event data
   * @return the value, or empty when the data lacks what the event type needs
   */
  static Optional<FieldRevocation> parse(String eventType, @Nullable JsonNode data) {
    if (data == null || !data.isObject()) {
      return Optional.empty();
    }
    switch (eventType) {
      case WORKER_SUSPENDED -> {
        String worker = text(data, "workerId");
        return worker == null ? Optional.empty() : Optional.of(new WorkerSuspended(worker, text(data, "clientId")));
      }
      case DEVICE_REVOKED -> {
        String device = text(data, "deviceId");
        return device == null ? Optional.empty() : Optional.of(new DeviceRevoked(device, text(data, "clientId")));
      }
      case SESSION_REVOKED -> {
        String session = text(data, "familyId");
        String worker = text(data, "workerId");
        String device = text(data, "deviceId");
        if (session == null && worker == null && device == null) {
          return Optional.empty();
        }
        return Optional.of(new SessionRevoked(session, worker, device, text(data, "clientId"), text(data, "reason")));
      }
      default -> {
        return Optional.empty();
      }
    }
  }

  /**
   * A subscription that delivers the three events to a path of this application's dispatch
   * receiver, for {@link Definitions#subscription}. Declare it so the definition sync registers it.
   *
   * @param code the subscription code
   * @param target where the platform delivers: the receiver's {@code dispatch} path
   * @return the subscription
   */
  static Definition.Subscription subscription(String code, String target) {
    return Definition.Subscription.of(code, "Field identity revocations", target, EVENT_TYPES.toArray(String[]::new));
  }

  /** What handles a revocation. */
  @FunctionalInterface
  interface Handler {

    /**
     * Handles one revocation. May run more than once for one event (a retry after a lost response):
     * make it idempotent. An exception makes the platform retry.
     *
     * @param ctx the context of the delivery
     * @param event the revocation
     * @throws Exception any failure; the platform retries
     */
    void handle(Context ctx, FieldRevocation event) throws Exception;
  }

  private static @Nullable String text(JsonNode data, String name) {
    JsonNode v = data.get(name);
    return v != null && v.isString() && !v.asString().isBlank() ? v.asString() : null;
  }
}
