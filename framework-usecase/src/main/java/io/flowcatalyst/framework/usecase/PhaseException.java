package io.flowcatalyst.framework.usecase;

/** A failure inside one named step of a composed execute (load, record-level check). */
final class PhaseException extends Exception {

  private static final long serialVersionUID = 1L;

  private final String step;
  private final transient Throwable failure;

  PhaseException(String step, Throwable cause) {
    super(step, cause);
    this.step = step;
    this.failure = cause;
  }

  Throwable failure() {
    return failure;
  }

  String step() {
    return step;
  }
}
