package io.flowcatalyst.framework.admin;

import java.util.List;

/**
 * The checked registry as a service: every section modules registered, in registration order (the
 * framework's {@code about} first). Provided by {@link AdminModule}; building it is where the
 * registry-wide start-up rules run.
 *
 * @param sections the sections
 * @param operatorsOnlyArea whether the admin area was declared for platform operators only
 *     ({@link AdminModule.Option#OPERATORS_ONLY})
 */
public record AdminRegistry(List<AdminSection> sections, boolean operatorsOnlyArea) {

  /** Copies the list. */
  public AdminRegistry {
    sections = List.copyOf(sections);
  }

  /**
   * A registry of an area that is not operators-only.
   *
   * @param sections the sections
   */
  public AdminRegistry(List<AdminSection> sections) {
    this(sections, false);
  }
}
