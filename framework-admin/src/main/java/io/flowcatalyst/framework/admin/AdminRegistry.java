package io.flowcatalyst.framework.admin;

import java.util.List;

/**
 * The checked registry as a service: every section modules registered, in registration order (the
 * framework's {@code about} first). Provided by {@link AdminModule}; building it is where the
 * registry-wide start-up rules run.
 *
 * @param sections the sections
 */
public record AdminRegistry(List<AdminSection> sections) {

  /** Copies the list. */
  public AdminRegistry {
    sections = List.copyOf(sections);
  }
}
