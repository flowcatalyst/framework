package io.flowcatalyst.framework.ui.kit;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The flash message rules of flash-stack.md, shared by the flash templates. */
public final class Flash {

  private Flash() {}

  /**
   * flash-stack.md's {@code datastar-fetch} listener: a non-2xx answer (except 401, which the
   * application handles) or a network failure appends one generic message from the stack's
   * templates.
   */
  public static final String FALLBACK_EXPR =
      "const t = evt.detail.type, s = evt.detail.argsRaw.status; "
          + "const k = t === 'retrying' || t === 'retries-failed' ? 'offline' : t === 'error' && s !== '401' ? (s === '403' ? 'refused' : 'failed') : ''; "
          + "k && !el.querySelector(':scope > [data-flash-fallback=' + k + ']') && el.append(el.querySelector(':scope > template[data-flash-template=' + k + ']').content.cloneNode(true))";

  /**
   * One generic message for a failed request.
   *
   * @param key refused, failed or offline
   * @param message the message
   */
  public record Fallback(String key, Props.FlashMessage message) {}

  private static final List<Fallback> FALLBACKS =
      List.of(
          fallback("refused", "error", "The request was refused.", "Reload the page and try again."),
          fallback(
              "failed",
              "error",
              "Something went wrong.",
              "The request failed. Try again, or reload the page."),
          fallback(
              "offline",
              "warn",
              "The server could not be reached.",
              "Check your connection and try again. A change you just made may not have been saved."));

  private static Fallback fallback(String key, String kind, String summary, String detail) {
    return new Fallback(
        key,
        new Props.FlashMessage(kind, summary, detail, false, Map.of("data-flash-fallback", key)));
  }

  /**
   * The generic messages, in the order the stack carries them.
   *
   * @return the fallbacks
   */
  public static List<Fallback> fallbacks() {
    return FALLBACKS;
  }

  /**
   * The kind, defaulting to success.
   *
   * @param kind success, info, warn or error; null for success
   * @return the kind
   */
  public static String kind(@Nullable String kind) {
    return Classes.oneOf(kind, "success", "flash kind", "success", "info", "warn", "error");
  }

  /**
   * Whether the message is {@code role="alert"} (warn and error).
   *
   * @param kind the kind
   * @return true for warn and error
   */
  public static boolean isAlert(@Nullable String kind) {
    String k = kind(kind);
    return k.equals("warn") || k.equals("error");
  }

  /**
   * The icon of a kind.
   *
   * @param kind the kind
   * @return the icon name
   */
  public static String icon(@Nullable String kind) {
    return switch (kind(kind)) {
      case "info" -> "info";
      case "warn" -> "triangle-alert";
      case "error" -> "circle-x";
      default -> "circle-check";
    };
  }

  /**
   * The auto-dismiss delay: 3s for success, 5s for info, none for warn, error or a persistent
   * message.
   *
   * @param kind the kind
   * @param persistent whether the message stays
   * @return {@code "3s"}, {@code "5s"} or an empty string
   */
  public static String lifetime(@Nullable String kind, boolean persistent) {
    if (isAlert(kind) || persistent) {
      return "";
    }
    return kind(kind).equals("info") ? "5s" : "3s";
  }
}
