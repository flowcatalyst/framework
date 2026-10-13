package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.ClientId;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who a request is authenticated as. Read it with {@link Auth#current}.
 *
 * @param principal the principal
 * @param client the client the {@code Context} carries
 * @param permissions what the application's mapping granted
 * @param source a browser session or a bearer token
 * @param expiresAt when the session or token stops being valid
 * @param idleExpiresAt for a session, when it expires without activity; null for a bearer token
 * @param authTime when the person signed in at the platform, for a session; null for a bearer token
 */
public record Authenticated(
    Principal principal,
    ClientId client,
    Set<String> permissions,
    Source source,
    Instant expiresAt,
    @Nullable Instant idleExpiresAt,
    @Nullable Instant authTime) {

  /** How a request was authenticated. */
  public enum Source {
    /** the session cookie */
    SESSION,
    /** an {@code Authorization: Bearer} platform access token */
    BEARER
  }

  /** Copies the permissions. */
  public Authenticated {
    permissions = Set.copyOf(permissions);
  }
}
