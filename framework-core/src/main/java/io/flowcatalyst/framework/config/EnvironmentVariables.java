package io.flowcatalyst.framework.config;

import java.util.Map;
import java.util.Optional;

/**
 * Reads environment variables by exact name: the environment the application was built with
 * ({@code AppBuilder.environment}, or the process environment). There is deliberately no way to
 * list them; code asks for the names it derives. Provided by the framework.
 */
public final class EnvironmentVariables {

  private final Map<String, String> variables;

  private EnvironmentVariables(Map<String, String> variables) {
    this.variables = Map.copyOf(variables);
  }

  /**
   * Wraps a map of variables.
   *
   * @param variables names to values
   * @return the reader
   */
  public static EnvironmentVariables of(Map<String, String> variables) {
    return new EnvironmentVariables(variables);
  }

  /**
   * One variable.
   *
   * @param name the exact name
   * @return its value, or empty when it is not set
   */
  public Optional<String> get(String name) {
    return Optional.ofNullable(variables.get(name));
  }

  @Override
  public String toString() {
    return "EnvironmentVariables[" + variables.size() + " variables]";
  }
}
