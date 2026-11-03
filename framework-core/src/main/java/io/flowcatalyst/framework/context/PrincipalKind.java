package io.flowcatalyst.framework.context;

/**
 * What kind of principal is acting: the platform's {@code type} claim (core contract section 3).
 */
public enum PrincipalKind {
  /** A person. */
  USER,
  /** A service account, such as the application's own when a receiver runs a use case. */
  SERVICE,
  /**
   * A field worker (driver, picker, yard-gate staff) who signed in to the platform's field plane
   * with a worker code and PIN on an enrolled device; principal id {@code fwu_...}. Set only by
   * the request resolver from a field access token. A field worker has no platform authority and
   * belongs to exactly one client. A {@code switch} over the kind must name this case: none of the
   * others may stand in for it.
   */
  FIELD_WORKER
}
