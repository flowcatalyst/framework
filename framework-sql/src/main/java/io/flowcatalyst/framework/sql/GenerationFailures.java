package io.flowcatalyst.framework.sql;

import java.util.List;
import java.util.stream.Collectors;

/** Every query or file that could not be generated, one per line in the message. */
public final class GenerationFailures extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<GenerationException> failures;

  /**
   * Creates the exception.
   *
   * @param failures the individual failures, at least one
   */
  public GenerationFailures(List<GenerationException> failures) {
    super(failures.stream().map(Throwable::getMessage).collect(Collectors.joining("\n")));
    this.failures = List.copyOf(failures);
  }

  /**
   * The failures.
   *
   * @return each with its file and line
   */
  public List<GenerationException> failures() {
    return failures;
  }
}
