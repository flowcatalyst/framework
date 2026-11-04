package io.flowcatalyst.framework.ui.i18n;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The messages of one request: a {@link Bundle} and the request's locale. {@code
 * messages.t("kit.list.next")}; {@code messages.t("kit.list.rows", Map.of("count", n))}.
 *
 * <p>While a page renders, the kit's templates read the request's messages from {@link #current()};
 * {@link io.flowcatalyst.framework.ui.Views} binds them for every page and patch it writes, and
 * {@link #where} binds them for an application that renders kit components itself.
 */
public final class Messages {

  private static final ScopedValue<Messages> CURRENT = ScopedValue.newInstance();
  private static final Messages ENGLISH = Bundle.kit().messages(Locales.DEFAULT);

  private final Bundle bundle;
  private final String locale;

  Messages(Bundle bundle, String locale) {
    this.bundle = bundle;
    this.locale = locale;
  }

  /**
   * English messages of the kit alone, the default when nothing is bound.
   *
   * @return the messages
   */
  public static Messages english() {
    return ENGLISH;
  }

  /**
   * The messages bound to the current rendering, or {@link #english()}.
   *
   * @return the messages
   */
  public static Messages current() {
    return CURRENT.isBound() ? CURRENT.get() : ENGLISH;
  }

  /**
   * Runs {@code body} with these messages as {@link #current()}. Content is lazy: render inside the
   * call.
   *
   * @param <T> the result type
   * @param messages the messages
   * @param body the rendering
   * @return what the body returns
   * @throws Exception what the body throws
   */
  public static <T> T where(Messages messages, Callable<T> body) throws Exception {
    return ScopedValue.where(CURRENT, messages).call(body::call);
  }

  /**
   * The request's locale (a BCP 47 tag, what {@code <html lang>} carries).
   *
   * @return the tag
   */
  public String locale() {
    return locale;
  }

  /**
   * The bundle.
   *
   * @return the bundle
   */
  public Bundle bundle() {
    return bundle;
  }

  /**
   * The message for a key.
   *
   * @param key the key
   * @return the text; the key itself when no catalog has it
   */
  public String t(String key) {
    return bundle.message(locale, key, Map.of());
  }

  /**
   * The message for a key with arguments.
   *
   * @param key the key
   * @param args placeholder values, and {@code count} for a plural message
   * @return the text; the key itself when no catalog has it
   */
  public String t(String key, Map<String, ?> args) {
    return bundle.message(locale, key, args);
  }

  /**
   * The text of a field error: {@code validation.<code>} with the error's parameters as arguments,
   * or the error's own message when no catalog has the key or the text needs a parameter the error
   * did not carry.
   *
   * @param code the validation code, for example {@code TOO_SHORT}
   * @param params the error's parameters
   * @param message the error's own message
   * @return the text to show
   */
  public String validation(String code, Map<String, ?> params, String message) {
    return bundle.validation(locale, code, params, message);
  }

  /**
   * Whether the bundle has the key for the locale or a fallback.
   *
   * @param key the key
   * @return true when found
   */
  public boolean has(String key) {
    return bundle.has(locale, key);
  }
}
