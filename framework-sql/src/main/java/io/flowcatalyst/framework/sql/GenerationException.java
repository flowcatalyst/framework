package io.flowcatalyst.framework.sql;

import org.jspecify.annotations.Nullable;

/** A query file or query that cannot be turned into code. The message names the file and line. */
public final class GenerationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String file;
  private final int line;

  /**
   * Creates the exception.
   *
   * @param file the query file, as it should be shown
   * @param line the 1-based line, or {@code 0} when the whole file is meant
   * @param message what is wrong
   */
  public GenerationException(String file, int line, String message) {
    this(file, line, message, null);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param file the query file, as it should be shown
   * @param line the 1-based line, or {@code 0} when the whole file is meant
   * @param message what is wrong
   * @param cause the underlying failure, or null
   */
  public GenerationException(
      String file, int line, String message, @Nullable Throwable cause) {
    super(line > 0 ? file + ":" + line + ": " + message : file + ": " + message, cause);
    this.file = file;
    this.line = line;
  }

  /**
   * The file.
   *
   * @return the file as shown in the message
   */
  public String file() {
    return file;
  }

  /**
   * The line.
   *
   * @return the 1-based line, or {@code 0}
   */
  public int line() {
    return line;
  }
}
