package io.flowcatalyst.framework.config;

/** Raised by a {@link SettingType} when raw text does not have the expected shape. */
public final class ValueFormatException extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param expected what the value should have looked like, for example "a duration such as 30s"
   */
  public ValueFormatException(String expected) {
    super(expected, null, false, false);
  }
}
