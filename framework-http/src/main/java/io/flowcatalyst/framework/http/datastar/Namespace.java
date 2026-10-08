package io.flowcatalyst.framework.http.datastar;

/** The XML namespace of patched elements. {@link #HTML} is the default. */
public enum Namespace {
  /** HTML (the default; not written to the wire). */
  HTML("html"),
  /** SVG. */
  SVG("svg"),
  /** MathML. */
  MATHML("mathml");

  private final String wire;

  Namespace(String wire) {
    this.wire = wire;
  }

  /**
   * The value written after {@code data: namespace }.
   *
   * @return the wire name
   */
  public String wire() {
    return wire;
  }
}
