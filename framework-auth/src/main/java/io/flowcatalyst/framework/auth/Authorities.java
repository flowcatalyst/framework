package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Request;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Client selection, permission mapping and the {@code Context} (contract section 7.3). */
final class Authorities {

  private final PermissionMapper permissions;
  private final ClientSelector clients;
  private final @Nullable ApplicationCode application;

  Authorities(PermissionMapper permissions, ClientSelector clients, @Nullable ApplicationCode application) {
    this.permissions = permissions;
    this.clients = clients;
    this.application = application;
  }

  Authenticated authenticate(
      Principal principal,
      Request request,
      Authenticated.Source source,
      Instant expiresAt,
      @Nullable Instant idleExpiresAt,
      @Nullable Instant authTime)
      throws Exception {
    ClientId client = clients.select(principal, request);
    if (client == null) {
      throw new IllegalStateException("the ClientSelector returned null");
    }
    if (!client.equals(ClientSelector.PLATFORM) && !principal.mayActFor(client)) {
      throw new HttpException(403, "CLIENT_NOT_PERMITTED", "the principal may not act for this client");
    }
    Set<String> granted = permissions.permissions(principal);
    return new Authenticated(principal, client, granted == null ? Set.of() : granted, source, expiresAt, idleExpiresAt, authTime);
  }

  /**
   * Whether a field worker may act for the client the request names (contract section 15.5, amended
   * 2026-11-07): the client selector is consulted as for anyone else, and a client other than the
   * worker's own (by identifier or platform id) is refused as a user of another client is. {@link
   * ClientSelector#PLATFORM} ("no particular client") leaves the worker's client.
   *
   * @return false when the request names another client
   */
  boolean fieldMayActFor(FieldPrincipal worker, Request request) throws Exception {
    ClientId selected = clients.select(fieldPrincipal(worker), request);
    if (selected == null) {
      throw new IllegalStateException("the ClientSelector returned null");
    }
    return selected.equals(ClientSelector.PLATFORM)
        || selected.value().equals(worker.clientIdentifier())
        || selected.value().equals(worker.clientId());
  }

  private static Principal fieldPrincipal(FieldPrincipal worker) {
    return new Principal(
        new PrincipalId(worker.workerId()), PrincipalKind.FIELD_WORKER, worker.name(), null, null, null, List.of(), List.of(), List.of(), Set.of());
  }

  /**
   * A verified field worker (contract section 15.5): the client is the token's (a request naming
   * another is refused first, {@link #fieldMayActFor}), and the permissions are those the field
   * mapping or resolver granted.
   */
  Authenticated field(FieldPrincipal worker, Collection<String> granted) {
    Principal principal = fieldPrincipal(worker);
    return new Authenticated(
        principal,
        new ClientId(worker.clientIdentifier()),
        Set.copyOf(granted),
        Authenticated.Source.FIELD,
        worker.expiresAt(),
        null,
        worker.authTime(),
        worker);
  }

  Context context(Authenticated a, Request request) {
    FieldPrincipal worker = a.fieldPrincipal();
    Context context = new Context(
        a.client(),
        a.principal().id(),
        a.principal().kind(),
        application,
        request.correlationId(),
        null,
        null,
        a.permissions());
    // the subject's display name: the field worker's, or the user's when the token carries one
    String name = a.principal().name();
    if (name != null && !name.isBlank()) {
      context = context.withSubjectName(name);
    }
    // the worker is the subject (the principal), the actor the operator
    return worker != null && worker.actor() != null ? context.withActor(worker.actor()) : context;
  }
}
