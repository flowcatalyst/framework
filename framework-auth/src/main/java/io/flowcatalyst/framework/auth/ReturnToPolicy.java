package io.flowcatalyst.framework.auth;

import org.jspecify.annotations.Nullable;

/**
 * Decides where a browser lands after signing in (contract section 4.1). Whatever a policy returns
 * is checked again: only a same-origin path is used, else the default landing.
 */
@FunctionalInterface
public interface ReturnToPolicy {

  /**
   * The path to land on.
   *
   * @param requested the {@code returnTo} query parameter, or null
   * @return a same-origin path, or null for the default landing
   */
  @Nullable String returnTo(@Nullable String requested);

  /**
   * The default: the requested value when it is a same-origin path.
   *
   * @return the policy
   */
  static ReturnToPolicy sameOriginPath() {
    return requested -> requested != null && isSameOriginPath(requested) ? requested : null;
  }

  /**
   * Whether a value is a same-origin path: starts with {@code /}, not {@code //}, no {@code \}, no
   * control character, at most 2048 characters.
   *
   * @param value the value
   * @return true when safe to redirect to
   */
  static boolean isSameOriginPath(String value) {
    if (value.isEmpty() || value.length() > 2048 || value.charAt(0) != '/' || value.startsWith("//")) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\' || c < 0x20 || c == 0x7f) {
        return false;
      }
    }
    return true;
  }
}
