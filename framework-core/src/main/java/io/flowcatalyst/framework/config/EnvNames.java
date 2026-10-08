package io.flowcatalyst.framework.config;

import java.util.Locale;

/** The environment-variable name mapping of the config contract. */
final class EnvNames {
  private EnvNames() {}

  /** Uppercases the key and replaces {@code .} and {@code -} with {@code _}. */
  static String of(String dottedKey) {
    return dottedKey.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
  }
}
