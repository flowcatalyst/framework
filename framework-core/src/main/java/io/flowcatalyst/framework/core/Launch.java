package io.flowcatalyst.framework.core;

/** What {@link AppBuilder#launch} did: started the application, or ran a command instead. */
public sealed interface Launch {

  /**
   * No command matched; the application started normally.
   *
   * @param app the running application
   */
  record Started(App app) implements Launch {}

  /**
   * A module's command ran and the application was closed.
   *
   * @param exitCode the code the command returned, or 1 when it threw
   */
  record Ran(int exitCode) implements Launch {}
}
