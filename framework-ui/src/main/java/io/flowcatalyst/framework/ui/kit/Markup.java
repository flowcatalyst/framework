package io.flowcatalyst.framework.ui.kit;

import gg.jte.Content;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Helpers the kit's templates use, and that application code writing its own markup around the kit
 * may use too: class joining, the validators for values that end up inside Datastar expressions,
 * and the one place attributes from a map are written.
 *
 * <p>Two rules run through the module. Every dynamic <em>text</em> or <em>attribute value</em> is
 * HTML-escaped (JTE's HTML mode, or {@link #attrs(Map)}). A value that ends up inside a Datastar
 * <em>expression</em> (an element id, a signal name, a path) is code, so it is validated against a
 * strict pattern and refused with {@link IllegalArgumentException} otherwise: it must never carry
 * user data.
 */
public final class Markup {

  private Markup() {}

  /** The sprite the specs and fixtures use; a page overrides it with {@link Kit#withSpriteUrl}. */
  public static final String DEFAULT_SPRITE_URL = "/icons/lucide-sprite.svg";

  private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
  private static final Pattern SIGNAL = Pattern.compile("[A-Za-z][A-Za-z0-9]*");
  private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9/_-]*");
  private static final Pattern ATTR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.:-]*");
  private static final Pattern KEBAB = Pattern.compile("([a-z0-9])([A-Z])");

  /** The class of the dialog close buttons: closes the dialog containing the element. */
  public static final String CLOSE_DIALOG = "el.closest('dialog').close()";

  /** Closes a full-viewport dialog when its mask is clicked. */
  public static final String LIGHT_DISMISS = "evt.target === el && el.close()";

  /**
   * The submit handler of a form that posts through Datastar: the form's own fields go to its own
   * action, network retries are off (a POST that reached the server must not be sent twice).
   */
  public static final String SUBMIT_FORM = "@post(el.action, {contentType: 'form', retryMaxCount: 0})";

  /** Submits a confirm dialog's form and closes the dialog. */
  public static final String SUBMIT_CONFIRM = SUBMIT_FORM + "; el.closest('dialog').close()";

  /** The expression a filter select uses to submit its form. */
  public static final String SUBMIT_ON_CHANGE = "el.form.requestSubmit()";

  /**
   * Joins class lists, skipping empty parts. Classes are concatenated, never merged.
   *
   * @param parts class lists, some possibly null or blank
   * @return the joined classes
   */
  public static String cx(@Nullable String... parts) {
    StringBuilder out = new StringBuilder();
    for (String p : parts) {
      if (p == null) {
        continue;
      }
      String t = p.strip();
      if (!t.isEmpty()) {
        if (!out.isEmpty()) {
          out.append(' ');
        }
        out.append(t);
      }
    }
    return out.toString();
  }

  /**
   * An empty or null string as null, so an attribute written from it is omitted.
   *
   * @param s the value
   * @return null when empty
   */
  public static @Nullable String nz(@Nullable String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  /**
   * The value, or the default when it is null or empty.
   *
   * @param v the value
   * @param def the default
   * @return one of them
   */
  public static String or(@Nullable String v, String def) {
    return v == null || v.isEmpty() ? def : v;
  }

  /**
   * A positive number as an attribute value, anything else as null (omitted).
   *
   * @param n the number
   * @return the number or null
   */
  public static @Nullable Integer pos(int n) {
    return n > 0 ? n : null;
  }

  /**
   * Validates an element id that an expression or an aria reference will contain.
   *
   * @param id the id
   * @return the id
   * @throws IllegalArgumentException unless it matches {@code [A-Za-z][A-Za-z0-9_-]*}
   */
  public static String id(@Nullable String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new IllegalArgumentException("invalid element id (want [A-Za-z][A-Za-z0-9_-]*)");
    }
    return id;
  }

  /**
   * Whether the id may be used as an element id or inside an expression.
   *
   * @param id the id
   * @return true when {@link #id(String)} accepts it
   */
  public static boolean validId(@Nullable String id) {
    return id != null && ID.matcher(id).matches();
  }

  /**
   * Validates a camelCase Datastar signal name.
   *
   * @param name the name
   * @return the name
   * @throws IllegalArgumentException unless it matches {@code [A-Za-z][A-Za-z0-9]*}
   */
  public static String signal(@Nullable String name) {
    if (name == null || !SIGNAL.matcher(name).matches()) {
      throw new IllegalArgumentException("invalid signal name (want camelCase [A-Za-z][A-Za-z0-9]*)");
    }
    return name;
  }

  /**
   * The kebab-case form of a signal name, for attribute keys ({@code data-bind:} + {@code
   * signalAttr("qPools")} is {@code data-bind:q-pools}): HTML lower-cases attribute names, Datastar
   * maps kebab-case back to camelCase.
   *
   * @param name the signal name
   * @return the attribute form
   */
  public static String signalAttr(String name) {
    return KEBAB.matcher(signal(name)).replaceAll("$1-$2").toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Validates a local path that an expression will contain.
   *
   * @param p the path
   * @return the path
   * @throws IllegalArgumentException unless it matches {@code /[A-Za-z0-9/_-]*}
   */
  public static String path(@Nullable String p) {
    if (p == null || !PATH.matcher(p).matches()) {
      throw new IllegalArgumentException("invalid path (want /[A-Za-z0-9/_-]*)");
    }
    return p;
  }

  /**
   * The expression that opens the {@code <dialog>} with this id (Datastar handlers, not
   * {@code commandfor}, for browser support).
   *
   * @param id the dialog id
   * @return the expression
   */
  public static String showModal(String id) {
    return "document.getElementById('" + id(id) + "').showModal()";
  }

  /**
   * The trigger expression for a confirm dialog shared by many records: the trigger's {@code
   * data-action} attribute (a plain attribute, so a URL never enters the expression) becomes the
   * form's action, then the dialog opens.
   *
   * @param id the dialog id
   * @return the expression
   */
  public static String openConfirm(String id) {
    return "const d = document.getElementById('"
        + id(id)
        + "'); d.querySelector('form').action = el.dataset.action; d.showModal()";
  }

  /**
   * The attributes a trigger needs to open dialog {@code id}: the click handler and {@code
   * aria-haspopup}.
   *
   * @param id the dialog id
   * @return attributes for {@code attrs}
   */
  public static Map<String, Object> openModal(String id) {
    return Map.of("data-on:click", showModal(id), "aria-haspopup", "dialog");
  }

  /**
   * Writes attributes from a map: {@code true} as a bare attribute, {@code false} and null omitted,
   * anything else as an escaped quoted value. Names are checked: a name that is not a plain
   * attribute name, or that starts with {@code on} (an inline event handler, which the page's
   * policy forbids and which is script from a string), is refused.
   *
   * @param attrs the attributes, or null
   * @return text starting with a space for each attribute, empty when there are none
   * @throws IllegalArgumentException for a refused name
   */
  public static String attrs(@Nullable Map<String, Object> attrs) {
    if (attrs == null || attrs.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Object> e : attrs.entrySet()) {
      String name = e.getKey();
      if (!ATTR_NAME.matcher(name).matches() || name.regionMatches(true, 0, "on", 0, 2)) {
        throw new IllegalArgumentException("attribute name refused: " + escape(name));
      }
      Object v = e.getValue();
      if (v == null || Boolean.FALSE.equals(v)) {
        continue;
      }
      out.append(' ').append(name);
      if (!Boolean.TRUE.equals(v)) {
        out.append("=\"").append(escape(String.valueOf(v))).append('"');
      }
    }
    return out.toString();
  }

  /**
   * HTML-escapes text for element content or a quoted attribute value.
   *
   * @param s the text
   * @return the escaped text
   */
  public static String escape(String s) {
    StringBuilder out = null;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      String rep =
          switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            case '\'' -> "&#39;";
            default -> null;
          };
      if (rep != null) {
        if (out == null) {
          out = new StringBuilder(s.length() + 16).append(s, 0, i);
        }
        out.append(rep);
      } else if (out != null) {
        out.append(c);
      }
    }
    return out == null ? s : out.toString();
  }

  /**
   * Markup the caller vouches for, as a slot. Never pass request data here.
   *
   * @param html trusted HTML
   * @return a content that writes it unchanged
   */
  public static Content raw(String html) {
    return output -> output.writeContent(html);
  }

  /**
   * Text as a slot, escaped.
   *
   * @param text the text
   * @return a content that writes it escaped
   */
  public static Content text(String text) {
    String escaped = escape(text);
    return output -> output.writeContent(escaped);
  }

  /**
   * Renders a content to a string (the HTML-escaping rules are the templates', not the sink's).
   *
   * @param content the content
   * @return the markup
   */
  public static String render(Content content) {
    gg.jte.output.StringOutput out = new gg.jte.output.StringOutput();
    content.writeTo(out);
    return out.toString();
  }
}
