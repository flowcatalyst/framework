package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.ClientId;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who a request is authenticated as. Read it with {@link Auth#current}.
 *
 * @param principal the principal
 * @param client the client the {@code Context} carries
 * @param permissions what the application's mapping granted
 * @param source a browser session, a bearer token or a field worker's token
 * @param expiresAt when the session or token stops being valid
 * @param idleExpiresAt for a session, when it expires without activity; null for a bearer token
 * @param authTime when the person signed in at the platform, for a session; null for a bearer token
 * @param fieldPrincipal the field worker, when {@code source} is {@link Source#FIELD}; read it with
 *     {@link #field()}
 */
public record Authenticated(
    Principal principal,
    ClientId client,
    Set<String> permissions,
    Source source,
    Instant expiresAt,
    @Nullable Instant idleExpiresAt,
    @Nullable Instant authTime,
    @Nullable FieldPrincipal fieldPrincipal) {

  /** How a request was authenticated. */
  public enum Source {
    /** the session cookie */
    SESSION,
    /** an {@code Authorization: Bearer} platform access token */
    BEARER,
    /** an {@code Authorization: DPoP} field access token with its proof (contract section 15) */
    FIELD
  }

  /** Copies the permissions. */
  public Authenticated {
    permissions = Set.copyOf(permissions);
    if ((source == Source.FIELD) != (fieldPrincipal != null)) {
      throw new IllegalArgumentException("a field principal goes with the FIELD source, and only with it");
    }
  }

  /** A session or bearer principal, which has no field part. */
  public Authenticated(
      Principal principal,
      ClientId client,
      Set<String> permissions,
      Source source,
      Instant expiresAt,
      @Nullable Instant idleExpiresAt,
      @Nullable Instant authTime) {
    this(principal, client, permissions, source, expiresAt, idleExpiresAt, authTime, null);
  }

  /**
   * The field worker.
   *
   * @return the worker for a request authenticated with a field token; empty for a session or a
   *     bearer token
   */
  public Optional<FieldPrincipal> field() {
    return Optional.ofNullable(fieldPrincipal);
  }
}
