package io.flowcatalyst.framework.core;

import java.io.PrintStream;
import java.util.List;

/**
 * A one-shot operation that needs only what modules <em>declared</em>, for example writing the
 * application's OpenAPI document ({@code java -jar app.jar openapi}). A module claims a name with
 * {@link AppBuilder#command(DeclarationCommand)} during {@code register}; {@link AppBuilder#launch}
 * runs it.
 *
 * <p>It runs after every module's {@code register} and nothing else: no configuration is loaded or
 * validated, no service is built, no {@code boot} or {@code started} hook runs, so it works without
 * a configuration file, the environment or a database. Command names are one namespace with
 * {@link Command}s.
 */
public interface DeclarationCommand {

  /**
   * The name the first program argument must equal, matching {@code [a-z][a-z0-9-]*}.
   *
   * @return the command name
   */
  String name();

  /**
   * Runs the command.
   *
   * @param declarations what the modules declared, read-only
   * @param args the program arguments after the command name
   * @param out where to write the output
   * @return the process exit code; 0 for success
   * @throws Exception any failure; it is logged and the exit code is 1
   */
  int run(Declarations declarations, List<String> args, PrintStream out) throws Exception;
}
