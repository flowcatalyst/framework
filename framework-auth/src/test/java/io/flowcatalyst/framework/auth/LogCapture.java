package io.flowcatalyst.framework.auth;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures what slf4j-simple writes (it writes to {@code System.err}, looked up per line) while
 * still passing it through. Tests in this module run one class at a time, so swapping the stream
 * is safe.
 */
final class LogCapture implements AutoCloseable {

  private final PrintStream original;
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

  private LogCapture() {
    this.original = System.err;
    OutputStream tee =
        new OutputStream() {
          @Override
          public void write(int b) {
            synchronized (captured) {
              captured.write(b);
            }
            original.write(b);
          }

          @Override
          public void write(byte[] b, int off, int len) {
            synchronized (captured) {
              captured.write(b, off, len);
            }
            original.write(b, off, len);
          }
        };
    System.setErr(new PrintStream(tee, true, StandardCharsets.UTF_8));
  }

  /** Starts capturing. */
  static LogCapture start() {
    return new LogCapture();
  }

  /** Everything logged so far. */
  String text() {
    synchronized (captured) {
      return captured.toString(StandardCharsets.UTF_8);
    }
  }

  @Override
  public void close() {
    System.setErr(original);
  }
}
