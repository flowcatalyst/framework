package io.flowcatalyst.framework.secrets;

/** The one rule for secret names, applied again by every store (a store may be called directly). */
final class Names {

  private Names() {}

  static void check(String name) {
    if (!SecretRef.isValidName(name)) {
      throw new IllegalArgumentException(
          "secret name must match [a-z0-9]+(-[a-z0-9]+)*, at most " + SecretRef.NAME_MAX + " characters");
    }
  }
}
