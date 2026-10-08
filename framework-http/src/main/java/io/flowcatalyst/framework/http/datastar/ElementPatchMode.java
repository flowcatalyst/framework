package io.flowcatalyst.framework.http.datastar;

/** How patched elements are applied to the page. {@link #OUTER} is the default. */
public enum ElementPatchMode {
  /** Morph the target element itself (the default; not written to the wire). */
  OUTER("outer"),
  /** Morph the target's children. */
  INNER("inner"),
  /** Replace the target element without morphing. */
  REPLACE("replace"),
  /** Insert at the start of the target's children. */
  PREPEND("prepend"),
  /** Insert at the end of the target's children. */
  APPEND("append"),
  /** Insert before the target. */
  BEFORE("before"),
  /** Insert after the target. */
  AFTER("after"),
  /** Remove the target. */
  REMOVE("remove");

  private final String wire;

  ElementPatchMode(String wire) {
    this.wire = wire;
  }

  /**
   * The value written after {@code data: mode }.
   *
   * @return the wire name
   */
  public String wire() {
    return wire;
  }
}
