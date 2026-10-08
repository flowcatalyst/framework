package io.flowcatalyst.framework.config;

import org.jspecify.annotations.Nullable;

/**
 * One problem found while loading configuration.
 *
 * @param module the module that declared the setting, when known
 * @param key the dotted key, when the problem concerns one
 * @param source where the offending value came from, when it has one
 * @param message what is wrong; never contains the raw text of a secret
 */
public record ConfigProblem(
    @Nullable String module, @Nullable String key, @Nullable String source, String message) {

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    if (module != null) {
      sb.append("module '").append(module).append("': ");
    }
    if (key != null) {
      sb.append("key '").append(key).append("'");
      if (source != null) {
        sb.append(" (from ").append(source).append(")");
      }
      sb.append(": ");
    } else if (source != null) {
      sb.append(source).append(": ");
    }
    return sb.append(message).toString();
  }
}
