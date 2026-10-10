package io.flowcatalyst.framework.secrets;

import java.util.regex.Pattern;

/** The one rule for secret names, applied again by every store (a store may be called directly). */
final class Names {

  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  private Names() {}

  static void check(String name) {
    if (name.length() > SecretRef.NAME_MAX || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "secret name must match [a-z0-9]+(-[a-z0-9]+)*, at most " + SecretRef.NAME_MAX + " characters");
    }
  }
}
