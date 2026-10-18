package io.flowcatalyst.framework.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The definition of one per-client setting: its key, the type of its value, the default a client
 * gets until it sets one, and an optional rule. A definition is a constant of the application
 * (contract section 2); the values are rows in the database.
 *
 * @param <T> the value type: anything Jackson reads and writes as JSON
 */
public final class ClientSetting<T> {

  private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9._-]{0,99}");

  private final String key;
  private final Class<T> type;
  private final T defaultValue;
  private final @Nullable Function<T, Optional<String>> rule;

  private ClientSetting(String key, Class<T> type, T defaultValue, @Nullable Function<T, Optional<String>> rule) {
    this.key = key;
    this.type = type;
    this.defaultValue = defaultValue;
    this.rule = rule;
  }

  /**
   * Defines a setting.
   *
   * @param <T> the value type
   * @param key {@code [a-z][a-z0-9._-]{0,99}}, unique in the application
   * @param type the value class
   * @param defaultValue what a client that never set it gets; must satisfy the rule, if one is added
   * @return the definition
   * @throws IllegalArgumentException when the key is malformed
   */
  public static <T> ClientSetting<T> of(String key, Class<T> type, T defaultValue) {
    if (!KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("a client setting key must match [a-z][a-z0-9._-]{0,99}: " + key);
    }
    return new ClientSetting<>(key, Objects.requireNonNull(type), Objects.requireNonNull(defaultValue), null);
  }

  /**
   * The same setting with a rule on its values: empty when the value is acceptable, otherwise a
   * message that is safe to show a person.
   *
   * @param check the rule
   * @return the definition with the rule
   * @throws IllegalArgumentException when the default does not satisfy the rule
   */
  public ClientSetting<T> rule(Function<T, Optional<String>> check) {
    Optional<String> bad = check.apply(defaultValue);
    if (bad.isPresent()) {
      throw new IllegalArgumentException("the default of client setting " + key + " breaks its own rule: " + bad.get());
    }
    return new ClientSetting<>(key, type, defaultValue, check);
  }

  /**
   * The key.
   *
   * @return the key
   */
  public String key() {
    return key;
  }

  /**
   * The value class.
   *
   * @return the class
   */
  public Class<T> type() {
    return type;
  }

  /**
   * What a client gets until it sets a value.
   *
   * @return the default
   */
  public T defaultValue() {
    return defaultValue;
  }

  /**
   * Checks a value against the rule.
   *
   * @param value the candidate
   * @return empty when acceptable, else the message
   */
  public Optional<String> check(T value) {
    Function<T, Optional<String>> check = rule;
    return check == null ? Optional.empty() : check.apply(value);
  }

  @Override
  public String toString() {
    return "ClientSetting[" + key + "]";
  }
}
