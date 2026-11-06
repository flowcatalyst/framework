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
   * A verified field worker (contract section 15.5): the client is the token's, the client selector
   * is not consulted, and the permissions are those the field mapping or resolver granted.
   */
  Authenticated field(FieldPrincipal worker, Collection<String> granted) {
    Principal principal =
        new Principal(
            new PrincipalId(worker.workerId()), PrincipalKind.FIELD_WORKER, worker.name(), null, null, null, List.of(), List.of(), List.of(), Set.of());
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
    // the worker is the subject (the principal), the actor the operator
    return worker != null && worker.actor() != null ? context.withActor(worker.actor()) : context;
  }
}
