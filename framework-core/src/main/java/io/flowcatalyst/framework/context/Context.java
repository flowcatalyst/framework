package io.flowcatalyst.framework.context;

import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Who is acting, for which client, in which application, and what it may do. Passed explicitly to
 * use cases and handlers; the framework never stores it in a static or a thread-local.
 *
 * @param client the client (tenant); always present
 * @param principal the acting principal, or null when none
 * @param principalKind what kind of principal it is, or null when there is no principal or its kind
 *     is unknown (the constructors that predate the kind); never present without a principal
 * @param application the application, or null when none
 * @param correlationId follows the request into logs and jobs; always present
 * @param causationId the id of the event or message that caused this work, or null
 * @param executionId the id of the job or run this work belongs to, or null
 * @param permissions what the principal may do; empty when nothing was granted. Entries follow the
 *     platform's form, {@code :}-separated segments where a held segment may be {@code *}; check
 *     them with {@link #hasPermission(String)}
 */
public record Context(
    ClientId client,
    @Nullable PrincipalId principal,
    @Nullable PrincipalKind principalKind,
    @Nullable ApplicationCode application,
    CorrelationId correlationId,
    @Nullable String causationId,
    @Nullable String executionId,
    Set<String> permissions) {

  /**
   * Copies the permissions.
   *
   * @throws IllegalArgumentException when a principal kind is given without a principal
   */
  public Context {
    if (principalKind != null && principal == null) {
      throw new IllegalArgumentException("a principal kind needs a principal");
    }
    permissions = Set.copyOf(permissions);
  }

  /**
   * A context whose principal's kind is unknown (the form before the kind existed).
   *
   * @param client the client
   * @param principal the acting principal, or null
   * @param application the application, or null
   * @param correlationId the correlation id
   * @param causationId the causation id, or null
   * @param executionId the execution id, or null
   * @param permissions what the principal may do
   */
  public Context(
      ClientId client,
      @Nullable PrincipalId principal,
      @Nullable ApplicationCode application,
      CorrelationId correlationId,
      @Nullable String causationId,
      @Nullable String executionId,
      Set<String> permissions) {
    this(client, principal, null, application, correlationId, causationId, executionId, permissions);
  }

  /**
   * A context without permissions.
   *
   * @param client the client
   * @param principal the acting principal, or null
   * @param application the application, or null
   * @param correlationId the correlation id
   * @param causationId the causation id, or null
   * @param executionId the execution id, or null
   */
  public Context(
      ClientId client,
      @Nullable PrincipalId principal,
      @Nullable ApplicationCode application,
      CorrelationId correlationId,
      @Nullable String causationId,
      @Nullable String executionId) {
    this(client, principal, application, correlationId, causationId, executionId, Set.of());
  }

  /**
   * A context without causation or execution ids and without permissions.
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
    this(client, principal, application, correlationId, null, null, Set.of());
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
   * Copies for another client, keeping everything else: principal, kind, application, correlation,
   * causation and execution ids, and permissions. For work that is not a request and has no client
   * of its own (a scheduled job, a background loop, a receiver's context for the platform) and
   * acts for one client after another: each step gets the context of its client, so a use case's
   * client scoping and authorization apply as for any caller. The permissions are the same set;
   * the check that a principal may act for the client is the use case's {@code authorize}, as ever.
   *
   * @param value the client the work is for
   * @return the new context
   */
  public Context forClient(ClientId value) {
    return new Context(value, principal, principalKind, application, correlationId, causationId, executionId, permissions);
  }

  /**
   * Copies with a principal whose kind is unknown; any kind held before is cleared, so it never
   * describes another principal.
   *
   * @param value the principal
   * @return the new context
   */
  public Context withPrincipal(PrincipalId value) {
    return new Context(client, value, null, application, correlationId, causationId, executionId, permissions);
  }

  /**
   * Copies with a principal and its kind.
   *
   * @param value the principal
   * @param kind what kind of principal it is
   * @return the new context
   */
  public Context withPrincipal(PrincipalId value, PrincipalKind kind) {
    return new Context(client, value, kind, application, correlationId, causationId, executionId, permissions);
  }

  /**
   * Copies with an application.
   *
   * @param value the application
   * @return the new context
   */
  public Context withApplication(ApplicationCode value) {
    return new Context(client, principal, principalKind, value, correlationId, causationId, executionId, permissions);
  }

  /**
   * Copies with a correlation id, for example one received from a caller.
   *
   * @param value the correlation id
   * @return the new context
   */
  public Context withCorrelationId(CorrelationId value) {
    return new Context(client, principal, principalKind, application, value, causationId, executionId, permissions);
  }

  /**
   * Copies with a causation id.
   *
   * @param value the id of the event or message that caused this work
   * @return the new context
   */
  public Context withCausationId(String value) {
    return new Context(client, principal, principalKind, application, correlationId, value, executionId, permissions);
  }

  /**
   * Copies with an execution id.
   *
   * @param value the id of the job or run this work belongs to
   * @return the new context
   */
  public Context withExecutionId(String value) {
    return new Context(client, principal, principalKind, application, correlationId, causationId, value, permissions);
  }

  /**
   * Copies with permissions, replacing any held before.
   *
   * @param value the permissions
   * @return the new context
   */
  public Context withPermissions(Set<String> value) {
    return new Context(client, principal, principalKind, application, correlationId, causationId, executionId, value);
  }

  /**
   * Whether a held permission matches the required one: the same number of {@code :}-separated
   * segments, each held segment {@code *} or equal to the required one (the platform's rule). A
   * blank requirement never matches.
   *
   * @param required the permission needed, for example {@code ingestor:ingestion:manage}
   * @return true when some held permission matches
   */
  public boolean hasPermission(String required) {
    if (required.isBlank()) {
      return false;
    }
    String[] wanted = required.split(":", -1);
    for (String held : permissions) {
      if (matches(held.split(":", -1), wanted)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(String[] held, String[] wanted) {
    if (held.length != wanted.length) {
      return false;
    }
    for (int i = 0; i < held.length; i++) {
      if (!held[i].equals("*") && !held[i].equals(wanted[i])) {
        return false;
      }
    }
    return true;
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
   * The principal's kind as an optional return value.
   *
   * @return the kind, if known
   */
  public Optional<PrincipalKind> findPrincipalKind() {
    return Optional.ofNullable(principalKind);
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
