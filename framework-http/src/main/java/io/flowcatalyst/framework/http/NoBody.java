package io.flowcatalyst.framework.http;

/**
 * The body or the response of an {@link Operation} that has none: a {@code GET}, a {@code DELETE},
 * a body-less {@code POST}, or a response answered {@code 204 No Content}.
 */
public final class NoBody {

  /** The only value. */
  public static final NoBody VALUE = new NoBody();

  private NoBody() {}

  @Override
  public String toString() {
    return "NoBody";
  }
}
