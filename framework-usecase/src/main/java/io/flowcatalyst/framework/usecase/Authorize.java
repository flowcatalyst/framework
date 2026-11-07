package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalKind;
import java.util.Optional;

/** Authorization helpers for use case definitions. */
public final class Authorize {

  /**
   * Declares an operation deliberately open: the use case is authorized for anyone. A use case
   * must still say so; leaving authorization out fails when the use case is defined.
   */
  public static final Check<Object> PUBLIC = (context, command) -> Optional.empty();

  /**
   * Grants only a service principal ({@link #requireService}): for operations that a receiver, a
   * process or a verified inbound webhook runs, never a person or a field worker.
   */
  public static final Check<Object> SERVICE = (context, command) -> requireService(context);

  private Authorize() {}

  /**
   * Grants when the context's principal is a service ({@code PrincipalKind.SERVICE}): the
   * receivers' contexts, and {@code Context.ofService(..)} for an inbound webhook.
   *
   * @param context who is acting
   * @return empty when granted, else an {@code AUTHORIZATION} error {@code SERVICE_REQUIRED}
   */
  public static Optional<UseCaseError> requireService(Context context) {
    return context.principal() != null && context.principalKind() == PrincipalKind.SERVICE
        ? Optional.empty()
        : Optional.of(UseCaseError.authorization("SERVICE_REQUIRED", "Only a service may do this."));
  }
}
