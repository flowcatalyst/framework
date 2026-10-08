package io.flowcatalyst.framework.context;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Who is acting, for which client, in which application. Passed explicitly to use cases and
 * handlers; the framework never stores it in a static or a thread-local.
 *
 * @param client the client (tenant); always present
 * @param principal the acting principal, or null when none
 * @param application the application, or null when none
 * @param correlationId follows the request into logs and jobs; always present
 * @param causationId the id of the event or message that caused this work, or null
 * @param executionId the id of the job or run this work belongs to, or null
 */
public record Context(
    ClientId client,
    @Nullable PrincipalId principal,
    @Nullable ApplicationCode application,
    CorrelationId correlationId,
    @Nullable String causationId,
    @Nullable String executionId) {

  /**
   * A context without causation or execution ids.
   *
   * @param client the client
   * @param principal the acting principal, or null
   * @param application the application, or null
   * @param correlationId the correlation id
   */
  public Context(
      ClientId client,
      @Nullable PrincipalId principal,
      @Nullable ApplicationCode application,
      CorrelationId correlationId) {
    this(client, principal, application, correlationId, null, null);
  }

  /**
   * A context for a client with a generated correlation id.
   *
   * @param client the client
   * @return the context
   */
  public static Context of(ClientId client) {
    return new Context(client, null, null, CorrelationId.generate());
  }

  /**
   * Copies with a principal.
   *
   * @param value the principal
   * @return the new context
   */
  public Context withPrincipal(PrincipalId value) {
    return new Context(client, value, application, correlationId, causationId, executionId);
  }

  /**
   * Copies with an application.
   *
   * @param value the application
   * @return the new context
   */
  public Context withApplication(ApplicationCode value) {
    return new Context(client, principal, value, correlationId, causationId, executionId);
  }

  /**
   * Copies with a correlation id, for example one received from a caller.
   *
   * @param value the correlation id
   * @return the new context
   */
  public Context withCorrelationId(CorrelationId value) {
    return new Context(client, principal, application, value, causationId, executionId);
  }

  /**
   * Copies with a causation id.
   *
   * @param value the id of the event or message that caused this work
   * @return the new context
   */
  public Context withCausationId(String value) {
    return new Context(client, principal, application, correlationId, value, executionId);
  }

  /**
   * Copies with an execution id.
   *
   * @param value the id of the job or run this work belongs to
   * @return the new context
   */
  public Context withExecutionId(String value) {
    return new Context(client, principal, application, correlationId, causationId, value);
  }

  /**
   * The principal as an optional return value.
   *
   * @return the principal, if present
   */
  public Optional<PrincipalId> findPrincipal() {
    return Optional.ofNullable(principal);
  }

  /**
   * The application as an optional return value.
   *
   * @return the application, if present
   */
  public Optional<ApplicationCode> findApplication() {
    return Optional.ofNullable(application);
  }
}
