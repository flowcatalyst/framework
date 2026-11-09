package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.context.Context;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Who a subscribe request authenticated as.
 *
 * @param context the request's context, as the application's resolver makes it
 * @param expiresAt when the credential (session, access token) stops being valid, or null when it
 *     is not known; the stream token expires no later
 * @param proofKey the thumbprint of the DPoP key the request proved it holds (a field token's
 *     {@code cnf.jkt}), {@code ""} when none: a ticket bound to a key ({@code cnf}) needs it
 * @param device the field device, for revocation, {@code ""} otherwise
 * @param session the field login family, for revocation, {@code ""} otherwise
 */
public record Credentials(Context context, @Nullable Instant expiresAt, String proofKey, String device, String session) {

  /**
   * Credentials of a context with no key, device or session.
   *
   * @param context the context
   * @param expiresAt when the credential expires, null when unknown
   * @return the credentials
   */
  public static Credentials of(Context context, @Nullable Instant expiresAt) {
    return new Credentials(context, expiresAt, "", "", "");
  }
}
