package io.flowcatalyst.framework.usecase;

import java.util.Optional;

/** Authorization helpers for use case definitions. */
public final class Authorize {

  /**
   * Declares an operation deliberately open: the use case is authorized for anyone. A use case
   * must still say so; leaving authorization out fails when the use case is defined.
   */
  public static final Check<Object> PUBLIC = (context, command) -> Optional.empty();

  private Authorize() {}
}
