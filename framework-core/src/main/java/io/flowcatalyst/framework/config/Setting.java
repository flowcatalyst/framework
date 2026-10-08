package io.flowcatalyst.framework.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A declared setting: key, type, kind, default, whether it is required, and a description.
 *
 * <p>A module declares each setting once, during {@code register}, and keeps the value to read it
 * after start-up with {@link Config#get(Setting)}.
 *
 * @param <T> the Java type of the value
 * @param key dotted lowercase key
 * @param type how values are parsed; also decides whether the setting is a secret
 * @param defaultValue the default, or null for none; never set on a secret
 * @param required whether start-up fails when no source gives a value (a default is a value)
 * @param description text for tooling
 */
public record Setting<T>(
    String key, SettingType<T> type, @Nullable T defaultValue, boolean required, String description) {

  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*");

  /** Validates the key and the secret rule. */
  public Setting {
    if (!KEY.matcher(key).matches()) {
      throw new IllegalArgumentException(
          "setting key '" + key + "' must match [a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)*");
    }
    if (type.isSecret() && defaultValue != null) {
      throw new IllegalArgumentException("secret setting '" + key + "' cannot have a default");
    }
  }

  private static <T> Setting<T> of(String key, SettingType<T> type) {
    return new Setting<>(key, type, null, false, "");
  }

  /**
   * A string setting.
   *
   * @param key the key
   * @return the declaration, optional and without default
   */
  public static Setting<String> string(String key) {
    return of(key, SettingType.STRING);
  }

  /**
   * An integer setting.
   *
   * @param key the key
   * @return the declaration, optional and without default
   */
  public static Setting<Integer> integer(String key) {
    return of(key, SettingType.INTEGER);
  }

  /**
   * A long setting.
   *
   * @param key the key
   * @return the declaration, optional and without default
   */
  public static Setting<Long> longValue(String key) {
    return of(key, SettingType.LONG);
  }

  /**
   * A boolean setting.
   *
   * @param key the key
   * @return the declaration, optional and without default
   */
  public static Setting<Boolean> bool(String key) {
    return of(key, SettingType.BOOLEAN);
  }

  /**
   * A duration setting.
   *
   * @param key the key
   * @return the declaration, optional and without default
   */
  public static Setting<Duration> duration(String key) {
    return of(key, SettingType.DURATION);
  }

  /**
   * A URI setting.
   *
   * @param key the key
   * @return the declaration, optional and without default
   */
  public static Setting<URI> uri(String key) {
    return of(key, SettingType.URI);
  }

  /**
   * A string list setting.
   *
   * @param key the key
   * @return the declaration, optional and without default
   */
  public static Setting<List<String>> stringList(String key) {
    return of(key, SettingType.STRING_LIST);
  }

  /**
   * An enum setting.
   *
   * @param <E> the enum type
   * @param key the key
   * @param values the enum's constants, for example {@code Mode.values()}
   * @return the declaration, optional and without default
   */
  public static <E extends Enum<E>> Setting<E> enumOf(String key, List<E> values) {
    return of(key, new SettingType.EnumType<>(values));
  }

  /**
   * A secret setting. It never has a default.
   *
   * @param key the key
   * @return the declaration, optional
   */
  public static Setting<Secret> secret(String key) {
    return of(key, SettingType.SECRET);
  }

  /**
   * Marks the setting required.
   *
   * @return a copy that fails start-up when no source gives a value
   */
  public Setting<T> asRequired() {
    return new Setting<>(key, type, defaultValue, true, description);
  }

  /**
   * Gives the setting a default.
   *
   * @param value the default
   * @return a copy with that default
   * @throws IllegalArgumentException for a secret
   */
  public Setting<T> withDefault(T value) {
    return new Setting<>(key, type, value, required, description);
  }

  /**
   * Sets the description.
   *
   * @param text text for tooling
   * @return a copy with that description
   */
  public Setting<T> describedAs(String text) {
    return new Setting<>(key, type, defaultValue, required, text);
  }

  /**
   * Whether this is a secret.
   *
   * @return true when the type is the secret type
   */
  public boolean isSecret() {
    return type.isSecret();
  }

  /**
   * The environment variable that overrides this setting.
   *
   * @return the key uppercased with {@code .} and {@code -} replaced by {@code _}
   */
  public String envName() {
    return EnvNames.of(key);
  }
}
