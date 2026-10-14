package io.flowcatalyst.framework.context;

/**
 * What kind of principal is acting: the platform's {@code type} claim (core contract section 3).
 */
public enum PrincipalKind {
  /** A person. */
  USER,
  /** A service account, such as the application's own when a receiver runs a use case. */
  SERVICE
}
