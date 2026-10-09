package io.flowcatalyst.framework.core;

import java.io.PrintStream;
import java.util.List;

/**
 * A one-shot operation the application's {@code main} can run instead of serving, for example
 * {@code java -jar app.jar sync --dry-run}. A module claims a name with
 * {@link AppBuilder#command(Command)} during {@code register}; {@link AppBuilder#launch} runs it.
 *
 * <p>A command runs after configuration is validated and every service is built, but
 * <em>before</em> any module's {@code boot} or {@code started} hook, so nothing is listening and no
 * background work has begun. The application is closed when the command returns.
 */
public interface Command {

  /**
   * The name the first program argument must equal, matching {@code [a-z][a-z0-9-]*}.
   *
   * @return the command name
   */
  String name();

  /**
   * Runs the command.
   *
   * @param app the assembled application: services and configuration, nothing booted
   * @param args the program arguments after the command name
   * @param out where to write the report
   * @return the process exit code; 0 for success
   * @throws Exception any failure; it is logged and the exit code is 1
   */
  int run(App app, List<String> args, PrintStream out) throws Exception;
}
