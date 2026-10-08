package io.flowcatalyst.framework.config;

/**
 * A configuration problem found while a driver factory reads its {@link InstanceConfig}.
 *
 * <p>The start-up sequence turns it into an entry of the {@code StartupException}.
 */
public final class ConfigException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ConfigProblem problem;

  /**
   * Creates the exception.
   *
   * @param problem what is wrong
   */
  public ConfigException(ConfigProblem problem) {
    super(problem.toString(), null, false, false);
    this.problem = problem;
  }

  /**
   * The problem.
   *
   * @return what is wrong
   */
  public ConfigProblem problem() {
    return problem;
  }
}
