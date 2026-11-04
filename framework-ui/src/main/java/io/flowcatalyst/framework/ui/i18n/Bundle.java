package io.flowcatalyst.framework.ui.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The layered catalogs of an application: the kit's first, the application's on top (contract
 * section 2). Immutable; {@link #with} returns a new bundle.
 */
public final class Bundle {

  /** The languages the kit ships a catalog for ({@code ui-kit/messages/<tag>.json}). */
  public static final List<String> KIT_LANGUAGES = List.of("en", "af");

  private static final String KIT_PATH = "/fw-ui/messages/";
  private static final Pattern PLACEHOLDER = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*");

  private static final Bundle KIT = new Bundle(List.of(loadKit()), null);

  private final List<Map<String, Catalog>> layers; // base first
  private final @Nullable BiConsumer<String, String> missing;

  private Bundle(List<Map<String, Catalog>> layers, @Nullable BiConsumer<String, String> missing) {
    this.layers = List.copyOf(layers);
    this.missing = missing;
  }

  private static Map<String, Catalog> loadKit() {
    Map<String, Catalog> m = new java.util.LinkedHashMap<>();
    for (String tag : KIT_LANGUAGES) {
      try (InputStream in = Bundle.class.getResourceAsStream(KIT_PATH + tag + ".json")) {
        if (in == null) {
          throw new IllegalStateException("kit catalog " + KIT_PATH + tag + ".json is missing");
        }
        m.put(tag, Catalog.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return m;
  }

  /**
   * The kit's own messages.
   *
   * @return the bundle
   */
  public static Bundle kit() {
    return KIT;
  }

  /**
   * A bundle of one layer.
   *
   * @param layer language tag to catalog
   * @return the bundle
   */
  public static Bundle of(Map<String, Catalog> layer) {
    return new Bundle(List.of(normalized(layer)), null);
  }

  /**
   * This bundle with a layer on top (an application's catalogs, which may override a kit message).
   *
   * @param layer language tag to catalog
   * @return the new bundle
   */
  public Bundle with(Map<String, Catalog> layer) {
    List<Map<String, Catalog>> all = new ArrayList<>(layers);
    all.add(normalized(layer));
    return new Bundle(all, missing);
  }

  /**
   * Reads an application's {@code i18n/<tag>.json} files from the classpath.
   *
   * @param loader where to look
   * @param directory the resource directory, for example {@code i18n}
   * @param tags the languages to read
   * @return a layer of the files that exist
   */
  public static Map<String, Catalog> loadClasspath(ClassLoader loader, String directory, List<String> tags) {
    Map<String, Catalog> m = new java.util.LinkedHashMap<>();
    for (String tag : tags) {
      try (InputStream in = loader.getResourceAsStream(directory + "/" + tag + ".json")) {
        if (in != null) {
          m.put(tag, Catalog.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return m;
  }

  private static Map<String, Catalog> normalized(Map<String, Catalog> layer) {
    Map<String, Catalog> m = new java.util.LinkedHashMap<>();
    layer.forEach((tag, c) -> m.put(Locales.normalize(tag), c));
    return m;
  }

  /**
   * A bundle that reports every lookup that finds the key in no catalog, not even English (the
   * lookup then renders the key). Tests use it to fail on a raw key.
   *
   * @param missing called with the locale and the key
   * @return the new bundle
   */
  public Bundle onMissing(BiConsumer<String, String> missing) {
    return new Bundle(layers, missing);
  }

  /**
   * The tags the bundle has a catalog for.
   *
   * @return the tags, sorted
   */
  public List<String> languages() {
    TreeSet<String> set = new TreeSet<>();
    layers.forEach(l -> set.addAll(l.keySet()));
    return List.copyOf(set);
  }

  /**
   * The messages for a request's locale.
   *
   * @param locale a BCP 47 tag
   * @return the messages
   */
  public Messages messages(String locale) {
    return new Messages(this, Locales.normalize(locale).isEmpty() ? Locales.DEFAULT : Locales.normalize(locale));
  }

  private record Found(Message message, String language) {}

  /** The tag, its language, then English, each once. */
  private static List<String> steps(String locale) {
    String tag = Locales.normalize(locale);
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (!tag.isEmpty()) {
      out.add(tag);
      out.add(Locales.language(tag));
    }
    out.add(Locales.DEFAULT);
    return List.copyOf(out);
  }

  private @Nullable Found find(String locale, String key) {
    for (String tag : steps(locale)) {
      for (int i = layers.size() - 1; i >= 0; i--) {
        Catalog c = layers.get(i).get(tag);
        Message m = c == null ? null : c.messages().get(key);
        if (m != null) {
          return new Found(m, Locales.language(tag));
        }
      }
    }
    return null;
  }

  /**
   * Whether some catalog has the key for the locale or a fallback.
   *
   * @param locale the locale
   * @param key the key
   * @return true when found
   */
  public boolean has(String locale, String key) {
    return find(locale, key) != null;
  }

  /**
   * The message for a key, with the arguments applied. A key no catalog has renders as itself.
   *
   * @param locale the locale
   * @param key the key
   * @param args placeholder values, and {@code count} for a plural message
   * @return the text
   */
  public String message(String locale, String key, Map<String, ?> args) {
    String text = lookup(locale, key, args, false);
    return text == null ? key : text;
  }

  /**
   * The text of a field error: the message {@code validation.<code>} with the error's parameters as
   * arguments, or {@code message} (the error's own English text) when no catalog has the key or the
   * text needs a parameter the error did not carry (contract section 4).
   *
   * @param locale the locale
   * @param code the validation code, for example {@code TOO_SHORT}
   * @param params the error's parameters
   * @param message the error's own message
   * @return the text to show
   */
  public String validation(String locale, String code, Map<String, ?> params, String message) {
    String text = lookup(locale, "validation." + code, params, true);
    return text == null ? message : text;
  }

  private @Nullable String lookup(String locale, String key, Map<String, ?> args, boolean strict) {
    Found f = find(locale, key);
    if (f == null) {
      if (missing != null) {
        missing.accept(locale, key);
      }
      return null;
    }
    String text =
        switch (f.message()) {
          case Message.Text t -> t.text();
          case Message.Plural p -> {
            String cat =
                args.containsKey("count") ? Plurals.category(f.language(), Locales.argText(args.get("count"))) : "other";
            String form = p.forms().get(cat);
            yield form != null ? form : java.util.Objects.requireNonNull(p.forms().get("other"));
          }
        };
    if (strict) {
      for (String name : placeholders(text)) {
        if (!args.containsKey(name)) {
          return null;
        }
      }
    }
    return format(text, args);
  }

  /**
   * Replaces {@code {name}} with the argument's text; an argument that is not given leaves the
   * placeholder as it is; {@code {{} and {@code }}} are a literal brace.
   *
   * @param text the message text
   * @param args the values
   * @return the text
   */
  public static String format(String text, Map<String, ?> args) {
    if (text.indexOf('{') < 0 && text.indexOf('}') < 0) {
      return text;
    }
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '{' && text.startsWith("{{", i)) {
        sb.append('{');
        i += 2;
      } else if (c == '}' && text.startsWith("}}", i)) {
        sb.append('}');
        i += 2;
      } else if (c == '{') {
        int end = text.indexOf('}', i);
        if (end > i + 1 && PLACEHOLDER.matcher(text.substring(i + 1, end)).matches()) {
          String name = text.substring(i + 1, end);
          if (args.containsKey(name)) {
            sb.append(Locales.argText(args.get(name)));
          } else {
            sb.append(text, i, end + 1);
          }
          i = end + 1;
        } else {
          sb.append(c);
          i++;
        }
      } else {
        sb.append(c);
        i++;
      }
    }
    return sb.toString();
  }

  /**
   * The placeholder names of a message text, sorted, without repeats.
   *
   * @param text the message text
   * @return the names
   */
  public static List<String> placeholders(String text) {
    TreeSet<String> set = new TreeSet<>();
    int i = 0;
    while (i < text.length()) {
      if (text.startsWith("{{", i) || text.startsWith("}}", i)) {
        i += 2;
      } else if (text.charAt(i) == '{') {
        int end = text.indexOf('}', i);
        Matcher m = end > i + 1 ? PLACEHOLDER.matcher(text.substring(i + 1, end)) : null;
        if (m != null && m.matches()) {
          set.add(text.substring(i + 1, end));
          i = end + 1;
        } else {
          i++;
        }
      } else {
        i++;
      }
    }
    return List.copyOf(set);
  }
}
