package io.flowcatalyst.framework.conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shared conformance vectors in {@code conformance/} at the repository root (Rust design,
 * milestones section 3). The Java framework is the reference: a vector test computes the expected
 * output with the Java code and compares it with the committed file, or, run with
 * {@code -Dconformance.update=true}, writes the file instead. The Rust tests only compare.
 *
 * <p>To add a vector: write its input into the file, run the module's tests with
 * {@code -Dconformance.update=true}, review the diff and commit it.
 */
public final class Conformance {

  private Conformance() {}

  /**
   * Whether the tests write the vectors instead of comparing them.
   *
   * @return true when {@code -Dconformance.update=true}
   */
  public static boolean updating() {
    return Boolean.getBoolean("conformance.update");
  }

  /**
   * The {@code conformance/} directory, found by walking up from the working directory.
   *
   * @return the directory
   * @throws IllegalStateException when no parent directory has {@code conformance/README.md}
   */
  public static Path root() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("conformance");
      if (Files.isRegularFile(candidate.resolve("README.md"))) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("no conformance/README.md above " + Path.of("").toAbsolutePath());
  }

  /**
   * A vector file.
   *
   * @param relative the path below {@code conformance/}, for example {@code tsid/tsid.json}
   * @return the path
   */
  public static Path file(String relative) {
    return root().resolve(relative);
  }

  /**
   * Reads a vector file as UTF-8.
   *
   * @param relative the path below {@code conformance/}
   * @return its text
   */
  public static String read(String relative) {
    try {
      return Files.readString(file(relative), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Compares a vector file with what the Java code produced, or writes it in update mode.
   *
   * @param relative the path below {@code conformance/}
   * @param actual the text the Java code produced, exactly as the file must hold it
   * @throws AssertionError when not updating and the file differs (or is missing)
   */
  public static void check(String relative, String actual) {
    Path path = file(relative);
    try {
      if (updating()) {
        Files.createDirectories(path.getParent());
        Files.writeString(path, actual, StandardCharsets.UTF_8);
        return;
      }
      if (!Files.exists(path)) {
        throw new AssertionError(
            "conformance vector " + relative + " is missing; run with -Dconformance.update=true");
      }
      String expected = Files.readString(path, StandardCharsets.UTF_8);
      if (!expected.equals(actual)) {
        throw new AssertionError(
            "conformance vector "
                + relative
                + " differs from what the Java code produces; if the change is intended, run with"
                + " -Dconformance.update=true and review the diff.\n--- expected (file)\n"
                + expected
                + "\n--- actual\n"
                + actual);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
