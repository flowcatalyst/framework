package io.flowcatalyst.framework.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a loaded configuration says about one declared setting, for tooling such as the admin config
 * page: where the value came from and whether there is one, and the value itself only when that is
 * safe to show. A secret's value is never here, and neither is the value of a setting whose key
 * names a credential even though it was declared as plain text; a URI is shown without user info and
 * without query.
 *
 * @param module the module that declared the setting
 * @param key the dotted key
 * @param type the accepted shape, for example "an integer"
 * @param secret whether the setting is declared as a secret
 * @param required whether start-up fails without a value
 * @param state whether a source gave a value, the default applies, or nothing does
 * @param source "default", "not set", an environment variable or a file; never a value
 * @param value the value as text, or null when {@code withheld} or {@code state} is UNSET
 * @param withheld whether there is a value that is deliberately not shown
 * @param description the setting's description, possibly empty
 */
public record SettingStatus(
    String module,
    String key,
    String type,
    boolean secret,
    boolean required,
    State state,
    String source,
    @Nullable String value,
    boolean withheld,
    String description) {

  /** Where the effective value comes from. */
  public enum State {
    /** A file or the environment gave a value. */
    SET,
    /** No source gave one; the declared default applies. */
    DEFAULT,
    /** There is no value (an optional setting nothing sets). */
    UNSET
  }

  private static final Pattern CREDENTIAL_KEY =
      Pattern.compile("(?i)(secret|password|passwd|passphrase|token|credential|api[-_.]?key|private[-_.]?key|access[-_.]?key|signing[-_.]?key|salt)");

  /**
   * Builds the status of one setting.
   *
   * @param module the declaring module
   * @param setting the setting
   * @param parsed the effective typed value, or null when there is none
   * @param source where it came from
   * @return the status; a secret or credential-named value is withheld
   */
  static SettingStatus of(String module, Setting<?> setting, @Nullable Object parsed, String source) {
    State state;
    if (parsed == null) {
      state = State.UNSET;
    } else if (source.equals("default")) {
      state = State.DEFAULT;
    } else {
      state = State.SET;
    }
    boolean hide = setting.isSecret() || CREDENTIAL_KEY.matcher(setting.key()).find();
    String shown = null;
    if (parsed != null && !hide) {
      shown = render(parsed);
    }
    return new SettingStatus(
        module,
        setting.key(),
        setting.type().describe(),
        setting.isSecret(),
        setting.required(),
        state,
        source,
        shown,
        parsed != null && hide,
        setting.description());
  }

  private static String render(Object value) {
    if (value instanceof URI uri) {
      return stripUri(uri);
    }
    if (value instanceof List<?> list) {
      return String.join(",", list.stream().map(Object::toString).toList());
    }
    if (value instanceof Enum<?> e) {
      return e.name();
    }
    return value.toString();
  }

  private static String stripUri(URI uri) {
    String authority = uri.getRawAuthority();
    if (authority == null) {
      // opaque or relative (jdbc:postgresql://... is opaque): keep the scheme-specific part, cut at ? and user info
      String text = uri.toString();
      return cutQueryAndUserInfo(text);
    }
    try {
      String host = uri.getHost() == null ? authority.substring(authority.indexOf('@') + 1) : uri.getHost();
      String hostPort = uri.getPort() >= 0 && uri.getHost() != null ? host + ":" + uri.getPort() : host;
      return new URI(uri.getScheme(), hostPort, uri.getPath(), null, null).toString();
    } catch (URISyntaxException e) {
      return cutQueryAndUserInfo(uri.toString());
    }
  }

  private static String cutQueryAndUserInfo(String text) {
    int q = text.indexOf('?');
    String base = q < 0 ? text : text.substring(0, q);
    int scheme = base.indexOf("://");
    if (scheme >= 0) {
      int at = base.indexOf('@', scheme + 3);
      int slash = base.indexOf('/', scheme + 3);
      if (at >= 0 && (slash < 0 || at < slash)) {
        base = base.substring(0, scheme + 3) + base.substring(at + 1);
      }
    }
    return base;
  }
}
