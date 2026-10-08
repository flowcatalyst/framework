package io.flowcatalyst.framework.config;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The merged file sources and the environment, shared by {@link Config} and its sections.
 *
 * @param leaves flattened file contents (defaults excluded), later files already applied
 * @param env the environment, consulted only for names derived from keys
 */
record Sources(Map<String, Leaf> leaves, Map<String, String> env) {

  /** One flattened value from a file; a null value marks an empty mapping or an explicit null. */
  record Leaf(@Nullable Object value, String source) {}
}
