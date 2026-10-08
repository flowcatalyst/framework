package io.flowcatalyst.framework.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One section of configuration read by a driver factory, for example
 * {@code locks.instances.shared}.
 *
 * <p>Each key is resolved from the environment variable derived from its full path, then from the
 * files (and, in {@code local} and {@code test}, {@code secrets.local.yaml}). Every key read is
 * remembered, so the caller can report keys that were never read as unknown.
 *
 * <p>Getters throw {@link ConfigException} when a key is missing or malformed.
 */
public final class InstanceConfig {

  private final String path;
  private final Sources sources;
  private final Set<String> read;

  InstanceConfig(String path, Sources sources, Set<String> read) {
    this.path = path;
    this.sources = sources;
    this.read = read;
  }

  static InstanceConfig root(String path, Sources sources) {
    return new InstanceConfig(path, sources, new HashSet<>());
  }

  /**
   * The dotted path of this section.
   *
   * @return for example {@code locks.instances.shared}
   */
  public String path() {
    return path;
  }

  /**
   * A nested section.
   *
   * @param key the nested key, for example {@code pool}
   * @return the section; reads through it count as reads of this section
   */
  public InstanceConfig section(String key) {
    return new InstanceConfig(path + "." + key, sources, read);
  }

  /**
   * The names directly under this section, from the files only.
   *
   * @return distinct first segments, in file order
   */
  public Set<String> keys() {
    String prefix = path + ".";
    Set<String> names = new LinkedHashSet<>();
    for (String full : sources.leaves().keySet()) {
      if (full.startsWith(prefix)) {
        String rest = full.substring(prefix.length());
        int dot = rest.indexOf('.');
        names.add(dot < 0 ? rest : rest.substring(0, dot));
      }
    }
    return names;
  }

  /**
   * Whether a value exists for the key in any source.
   *
   * @param key the key within this section
   * @return true when the environment or a file gives a value
   */
  public boolean has(String key) {
    return find(key) != null;
  }

  /**
   * Keys under this section, at any depth, that exist in the files but were never read.
   *
   * @return relative dotted keys, in file order
   */
  public List<String> unreadKeys() {
    String prefix = path + ".";
    List<String> unread = new ArrayList<>();
    for (String full : sources.leaves().keySet()) {
      if (full.startsWith(prefix) && !read.contains(full)) {
        unread.add(full.substring(prefix.length()));
      }
    }
    return unread;
  }

  /**
   * Source description for a key that exists, for error messages.
   *
   * @param key the key within this section
   * @return where the winning value came from, or "not set"
   */
  public String sourceOf(String key) {
    Found f = find(key);
    return f == null ? "not set" : f.source();
  }

  /**
   * Reads a typed value.
   *
   * @param <T> the Java type
   * @param key the key within this section
   * @param type how to parse it
   * @return the value if some source gives one
   * @throws ConfigException when the value is malformed
   */
  public <T> Optional<T> find(String key, SettingType<T> type) {
    String full = path + "." + key;
    read.add(full);
    Found f = find(key);
    if (f == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(type.parse(f.raw()));
    } catch (ValueFormatException e) {
      String message =
          type.isSecret()
              ? "expected " + e.getMessage()
              : "expected " + e.getMessage() + ", found '" + describeRaw(f.raw()) + "'";
      throw new ConfigException(new ConfigProblem(null, full, f.source(), message));
    }
  }

  /**
   * Reads a required typed value.
   *
   * @param <T> the Java type
   * @param key the key within this section
   * @param type how to parse it
   * @return the value
   * @throws ConfigException when the key is missing or malformed
   */
  public <T> T get(String key, SettingType<T> type) {
    Optional<T> v = find(key, type);
    if (v.isEmpty()) {
      String full = path + "." + key;
      throw new ConfigException(
          new ConfigProblem(
              null,
              full,
              null,
              "missing required key; set it in a config file or as environment variable "
                  + EnvNames.of(full)));
    }
    return v.get();
  }

  /**
   * Reads a typed value with a default.
   *
   * @param <T> the Java type
   * @param key the key within this section
   * @param type how to parse it
   * @param defaultValue used when no source gives a value
   * @return the value
   * @throws ConfigException when the value is malformed
   */
  public <T> T get(String key, SettingType<T> type, T defaultValue) {
    return find(key, type).orElse(defaultValue);
  }

  /**
   * Reads a required string.
   *
   * @param key the key within this section
   * @return the value
   */
  public String string(String key) {
    return get(key, SettingType.STRING);
  }

  /**
   * Reads a string with a default.
   *
   * @param key the key within this section
   * @param defaultValue used when no source gives a value
   * @return the value
   */
  public String string(String key, String defaultValue) {
    return get(key, SettingType.STRING, defaultValue);
  }

  /**
   * Reads an optional string.
   *
   * @param key the key within this section
   * @return the value, if any
   */
  public Optional<String> findString(String key) {
    return find(key, SettingType.STRING);
  }

  /**
   * Reads an integer with a default.
   *
   * @param key the key within this section
   * @param defaultValue used when no source gives a value
   * @return the value
   */
  public int integer(String key, int defaultValue) {
    return get(key, SettingType.INTEGER, defaultValue);
  }

  /**
   * Reads a boolean with a default.
   *
   * @param key the key within this section
   * @param defaultValue used when no source gives a value
   * @return the value
   */
  public boolean bool(String key, boolean defaultValue) {
    return get(key, SettingType.BOOLEAN, defaultValue);
  }

  /**
   * Reads a duration with a default.
   *
   * @param key the key within this section
   * @param defaultValue used when no source gives a value
   * @return the value
   */
  public Duration duration(String key, Duration defaultValue) {
    return get(key, SettingType.DURATION, defaultValue);
  }

  /**
   * Reads a required secret.
   *
   * @param key the key within this section
   * @return the value
   */
  public Secret secret(String key) {
    return get(key, SettingType.SECRET);
  }

  /**
   * Reads an optional secret.
   *
   * @param key the key within this section
   * @return the value, if any
   */
  public Optional<Secret> findSecret(String key) {
    return find(key, SettingType.SECRET);
  }

  @Override
  public String toString() {
    return "InstanceConfig[path=" + path + ", keys=" + keys() + "]";
  }

  private record Found(Object raw, String source) {}

  private @Nullable Found find(String key) {
    String full = path + "." + key;
    String envName = EnvNames.of(full);
    String fromEnv = sources.env().get(envName);
    if (fromEnv != null) {
      return new Found(fromEnv, "environment variable " + envName);
    }
    Sources.Leaf leaf = sources.leaves().get(full);
    if (leaf != null) {
      Object value = leaf.value();
      if (value != null) {
        return new Found(value, leaf.source());
      }
    }
    return null;
  }

  private static String describeRaw(Object raw) {
    return switch (raw) {
      case List<?> l -> "a list";
      default -> String.valueOf(raw);
    };
  }
}
