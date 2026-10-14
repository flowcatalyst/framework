package io.flowcatalyst.framework.config;

import java.util.List;

/**
 * The checks {@link Config#load(java.nio.file.Path, java.util.Map, List, KeyRules)} applies to the
 * keys found in files and the environment (config-and-code contract, section 5).
 *
 * @param removed keys whose behaviour moved to code; giving one fails start-up
 * @param claims components whose keys must all be declared
 */
public record KeyRules(List<RemovedKey> removed, List<ComponentClaim> claims) {

  /** Copies the lists. */
  public KeyRules {
    removed = List.copyOf(removed);
    claims = List.copyOf(claims);
  }

  /**
   * No checks: the loader alone, as the conformance vectors exercise it.
   *
   * @return empty rules
   */
  public static KeyRules none() {
    return new KeyRules(List.of(), List.of());
  }

  /**
   * The framework's removed keys, whatever modules are listed, with the given claims. This is
   * what the application builder applies.
   *
   * @param claims the components modules claimed
   * @return the rules
   */
  public static KeyRules framework(List<ComponentClaim> claims) {
    return new KeyRules(FrameworkKeys.REMOVED, claims);
  }

  /**
   * The framework's removed keys (config-and-code contract, section 8).
   *
   * @return the list, most specific patterns first
   */
  public static List<RemovedKey> frameworkRemovedKeys() {
    return FrameworkKeys.REMOVED;
  }
}
