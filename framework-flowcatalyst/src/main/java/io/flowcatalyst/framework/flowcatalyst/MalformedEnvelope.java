package io.flowcatalyst.framework.flowcatalyst;

/** A delivery's body was not a valid envelope. The message names the field, never its value. */
final class MalformedEnvelope extends Exception {

  private static final long serialVersionUID = 1L;

  MalformedEnvelope(String message) {
    super(message, null, false, false);
  }
}
