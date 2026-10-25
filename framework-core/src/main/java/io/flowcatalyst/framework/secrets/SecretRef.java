package io.flowcatalyst.framework.secrets;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A reference to a per-client secret: which store, and the secret's name. It never names a
 * client; the client is the one the caller acts for (secrets contract, section 2).
 *
 * @param store the store instance, or null for the default instance
 * @param name the secret's name, {@code [a-z0-9]+(-[a-z0-9]+)*}, at most 100 characters
 */
public record SecretRef(@Nullable String store, String name) {

  private static final Pattern STORE = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  /** The longest secret name. */
  public static final int NAME_MAX = 100;

  /**
   * Whether text is a valid secret name: {@code [a-z0-9]+(-[a-z0-9]+)*}, at most {@link #NAME_MAX}
   * characters. The one definition; the admin page's property rule is this one.
   *
   * @param name the text
   * @return true when valid
   */
  public static boolean isValidName(String name) {
    return name.length() <= NAME_MAX && NAME.matcher(name).matches();
  }

  /** Checks both parts. */
  public SecretRef {
    if (store != null && (store.length() > 100 || !STORE.matcher(store).matches())) {
      throw new IllegalArgumentException("secret store name must match [a-z][a-z0-9-]*");
    }
    if (!isValidName(name)) {
      throw new IllegalArgumentException(
          "secret name must match [a-z0-9]+(-[a-z0-9]+)*, at most " + NAME_MAX + " characters");
    }
  }

  /**
   * Parses {@code name} or {@code store:name}.
   *
   * @param text the reference as stored by the application
   * @return the reference
   * @throws IllegalArgumentException when it is malformed
   */
  public static SecretRef parse(String text) {
    int colon = text.indexOf(':');
    try {
      return colon < 0
          ? new SecretRef(null, text)
          : new SecretRef(text.substring(0, colon), text.substring(colon + 1));
    } catch (IllegalArgumentException e) {
      String shown = text.length() > 200 ? "(" + text.length() + " characters)" : "'" + text + "'";
      throw new IllegalArgumentException("not a secret reference " + shown + ": " + e.getMessage(), e);
    }
  }

  /**
   * A reference to the default store.
   *
   * @param name the secret's name
   * @return the reference
   */
  public static SecretRef of(String name) {
    return new SecretRef(null, name);
  }

  @Override
  public String toString() {
    return store == null ? name : store + ":" + name;
  }
}
