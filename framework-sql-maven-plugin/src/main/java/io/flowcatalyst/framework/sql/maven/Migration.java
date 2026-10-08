package io.flowcatalyst.framework.sql.maven;

/**
 * One migration set to apply, as written in the plugin configuration:
 * {@code <migration><moduleId>outbox</moduleId><location>db/migration/outbox</location></migration>}.
 */
public final class Migration {

  /** The module id the history is keyed by. Set by Maven from the configuration. */
  String moduleId = "";

  /** The classpath directory holding the {@code V<version>__<name>.sql} files. */
  String location = "";

  /**
   * The module id.
   *
   * @return the id
   */
  public String moduleId() {
    return moduleId;
  }

  /**
   * The classpath location.
   *
   * @return the location
   */
  public String location() {
    return location;
  }
}
