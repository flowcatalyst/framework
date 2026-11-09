package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.push.Holder;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link FieldRevocation} into the push streams it ends (push contract section 7). The
 * application calls {@code Pushes.endStreams} with it from its field-revocation handler:
 *
 * <pre>{@code
 * receivers.onFieldRevocation((ctx, event) -> pushes.endStreams(PushHolders.of(event)));
 * }</pre>
 *
 * <p>Needs {@code framework-push} on the classpath (an optional dependency of this module).
 */
public final class PushHolders {

  private PushHolders() {}

  /**
   * The holder whose streams a revocation ends: the worker's for a suspension, the device's for a
   * revoked device, and for a revoked session the login family's (or, when the platform revoked
   * every session of a worker or a device, theirs).
   *
   * @param event the revocation
   * @return the holder
   */
  public static Holder of(FieldRevocation event) {
    return switch (event) {
      case FieldRevocation.WorkerSuspended w -> Holder.ofPrincipal(w.workerId());
      case FieldRevocation.DeviceRevoked d -> new Holder("", d.deviceId(), "");
      case FieldRevocation.SessionRevoked s -> new Holder(text(s.workerId()), text(s.deviceId()), text(s.sessionId()));
    };
  }

  private static String text(@Nullable String value) {
    return value == null ? "" : value;
  }
}
