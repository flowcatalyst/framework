package io.flowcatalyst.framework.core;

import java.util.List;
import java.util.stream.Collectors;

/** The application could not start. Lists every problem found, each naming its module and key. */
public final class StartupException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<Problem> problems;

  /**
   * Creates the exception.
   *
   * @param problems every problem found, never empty; their causes are attached as suppressed
   */
  public StartupException(List<Problem> problems) {
    super(message(problems));
    this.problems = List.copyOf(problems);
    for (Problem p : problems) {
      if (p.cause() != null) {
        addSuppressed(p.cause());
      }
    }
  }

  /**
   * Every problem found.
   *
   * @return the problems, in the order they were found
   */
  public List<Problem> problems() {
    return problems;
  }

  private static String message(List<Problem> problems) {
    return "Application failed to start: "
        + problems.size()
        + (problems.size() == 1 ? " problem" : " problems")
        + "\n"
        + problems.stream().map(p -> "  - " + p).collect(Collectors.joining("\n"));
  }
}
