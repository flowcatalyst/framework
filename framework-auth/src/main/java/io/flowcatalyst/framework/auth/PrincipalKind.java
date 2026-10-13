package io.flowcatalyst.framework.auth;

/** What kind of principal a token or session is for: the platform's {@code type} claim. */
public enum PrincipalKind {
  /** A person. */
  USER,
  /** A service account. */
  SERVICE;

  /** The kind named by a {@code type} claim; anything but {@code SERVICE} is a user. */
  static PrincipalKind of(String type) {
    return "SERVICE".equals(type) ? SERVICE : USER;
  }
}
