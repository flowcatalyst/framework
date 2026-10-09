package io.flowcatalyst.framework.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Resolved configuration: typed values for declared settings, and sections for instance
 * configuration. Values do not change after loading.
 *
 * <p>Sources, lowest to highest: the declaration's default; {@code application.yaml} then
 * {@code application-{env}.yaml}; {@code secrets.local.yaml} when {@code env} is {@code local} or
 * {@code test}; environment variables.
 */
public final class Config {

  private final String environment;
  private final Map<String, Setting<?>> declared;
  private final Map<String, Object> values;
  private final Sources sources;

  private Config(
      String environment,
      Map<String, Setting<?>> declared,
      Map<String, Object> values,
      Sources sources) {
    this.environment = environment;
    this.declared = declared;
    this.values = values;
    this.sources = sources;
  }

  /** The outcome of {@link #load}. */
  public sealed interface Loaded permits Ok, Failed {}

  /**
   * Configuration loaded without problems.
   *
   * @param config the configuration
   */
  public record Ok(Config config) implements Loaded {}

  /**
   * Configuration with problems; all of them are listed.
   *
   * @param problems every problem found, never empty
   */
  public record Failed(List<ConfigProblem> problems) implements Loaded {
    /** Copies the list. */
    public Failed {
      problems = List.copyOf(problems);
    }
  }

  /**
   * Loads and validates configuration.
   *
   * @param directory the config directory, normally {@code config/}
   * @param env the process environment (injected so tests do not touch the real one)
   * @param declarations every declared setting, in registration order
   * @return the configuration, or every problem found
   */
  public static Loaded load(
      Path directory, Map<String, String> env, List<Declaration> declarations) {
    List<ConfigProblem> problems = new ArrayList<>();

    String environment = env.get("APP_ENV");
    if (environment == null || environment.isBlank()) {
      environment = "local";
    }

    Map<String, Sources.Leaf> leaves = new LinkedHashMap<>();
    YamlFiles.read(directory.resolve("application.yaml"), leaves, problems);
    YamlFiles.read(directory.resolve("application-" + environment + ".yaml"), leaves, problems);
    if (environment.equals("local") || environment.equals("test")) {
      YamlFiles.read(directory.resolve("secrets.local.yaml"), leaves, problems);
    }
    Sources sources = new Sources(leaves, Map.copyOf(env));

    Map<String, Setting<?>> declared = new LinkedHashMap<>();
    Map<String, String> declaredBy = new LinkedHashMap<>();
    Map<String, Object> values = new LinkedHashMap<>();
    for (Declaration d : declarations) {
      Setting<?> s = d.setting();
      String earlier = declaredBy.get(s.key());
      if (earlier != null) {
        problems.add(
            new ConfigProblem(
                d.module(),
                s.key(),
                null,
                "declared twice (first by module '" + earlier + "')"));
        continue;
      }
      declared.put(s.key(), s);
      declaredBy.put(s.key(), d.module());
      resolve(d.module(), s, sources, values, problems);
    }

    checkEnvironmentNames(declarations, leaves, problems);

    if (!problems.isEmpty()) {
      return new Failed(problems);
    }
    return new Ok(new Config(environment, declared, values, sources));
  }

  /**
   * Fails when two different keys would read the same environment variable. Keys may contain
   * {@code _} and both {@code .} and {@code -} become {@code _}, so {@code a.b-c}, {@code a.b_c}
   * and {@code a.b.c} all read {@code A_B_C}: setting it would silently configure all of them.
   *
   * <p>Compared: every declared key with every other declared key, and the keys of instance
   * sections found in the files (those with an {@code instances} segment, such as {@code
   * database.instances.main.url}) with the declared keys and with each other.
   */
  private static void checkEnvironmentNames(
      List<Declaration> declarations,
      Map<String, Sources.Leaf> leaves,
      List<ConfigProblem> problems) {
    Map<String, String[]> claimed = new LinkedHashMap<>();
    for (Declaration d : declarations) {
      String key = d.setting().key();
      claim(
          claimed,
          EnvNames.of(key),
          key,
          "declared by module '" + d.module() + "'",
          d.module(),
          problems);
    }
    for (Map.Entry<String, Sources.Leaf> e : leaves.entrySet()) {
      String key = e.getKey();
      if (!(key.startsWith("instances.") || key.contains(".instances."))) {
        continue;
      }
      claim(
          claimed,
          EnvNames.of(key),
          key,
          "an instance setting in " + e.getValue().source(),
          null,
          problems);
    }
  }

  private static void claim(
      Map<String, String[]> claimed,
      String envName,
      String key,
      String label,
      @Nullable String module,
      List<ConfigProblem> problems) {
    String[] first = claimed.putIfAbsent(envName, new String[] {key, label});
    if (first != null && !first[0].equals(key)) {
      problems.add(
          new ConfigProblem(
              module,
              key,
              null,
              "reads environment variable "
                  + envName
                  + ", which key '"
                  + first[0]
                  + "' ("
                  + first[1]
                  + ") reads too; keys may contain '_' and both '.' and '-' become '_', so rename"
                  + " one of them"));
    }
  }

  private static void resolve(
      String module,
      Setting<?> s,
      Sources sources,
      Map<String, Object> values,
      List<ConfigProblem> problems) {
    String envName = s.envName();
    @Nullable Object raw = null;
    @Nullable String source = null;
    String fromEnv = sources.env().get(envName);
    if (fromEnv != null) {
      raw = fromEnv;
      source = "environment variable " + envName;
    } else {
      Sources.Leaf leaf = sources.leaves().get(s.key());
      if (leaf != null && leaf.value() != null) {
        raw = leaf.value();
        source = leaf.source();
      }
    }
    if (raw != null) {
      try {
        values.put(s.key(), s.type().parse(raw));
      } catch (ValueFormatException e) {
        String found = s.isSecret() ? "" : ", found '" + (raw instanceof List<?> ? "a list" : raw) + "'";
        problems.add(
            new ConfigProblem(module, s.key(), source, "expected " + e.getMessage() + found));
      }
      return;
    }
    Object def = s.defaultValue();
    if (def != null) {
      values.put(s.key(), def);
    } else if (s.required()) {
      problems.add(
          new ConfigProblem(
              module,
              s.key(),
              null,
              "missing required setting; set it in config/application.yaml"
                  + (s.isSecret() ? ", config/secrets.local.yaml (local and test only)" : "")
                  + " or as environment variable "
                  + envName));
    }
  }

  /**
   * The environment name.
   *
   * @return {@code APP_ENV}, default {@code local}
   */
  public String environment() {
    return environment;
  }

  /**
   * Reads a declared setting that has a value (it is required or has a default, or a source
   * gave it one).
   *
   * @param <T> the Java type
   * @param setting the setting, exactly as declared
   * @return the value
   * @throws IllegalArgumentException if the setting was not declared
   * @throws IllegalStateException if the setting is optional and no source gave it a value
   */
  public <T> T get(Setting<T> setting) {
    Optional<T> v = find(setting);
    if (v.isEmpty()) {
      throw new IllegalStateException(
          "setting '" + setting.key() + "' has no value; it is optional, use find()");
    }
    return v.get();
  }

  /**
   * Reads a declared setting that may have no value.
   *
   * @param <T> the Java type
   * @param setting the setting, exactly as declared
   * @return the value, if any
   * @throws IllegalArgumentException if the setting was not declared
   */
  public <T> Optional<T> find(Setting<T> setting) {
    Setting<?> d = declared.get(setting.key());
    if (d == null || !d.equals(setting)) {
      throw new IllegalArgumentException(
          "setting '" + setting.key() + "' was not declared (or was declared differently)");
    }
    Object v = values.get(setting.key());
    return v == null ? Optional.empty() : Optional.of(setting.type().cast(v));
  }

  /**
   * A section for instance configuration.
   *
   * @param path dotted path, for example {@code locks.instances.shared}
   * @return a section with its own record of which keys were read
   */
  public InstanceConfig section(String path) {
    return InstanceConfig.root(path, sources);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Config[environment=").append(environment);
    for (Map.Entry<String, Object> e : values.entrySet()) {
      sb.append(", ").append(e.getKey()).append('=');
      Setting<?> s = declared.get(e.getKey());
      sb.append(s != null && s.isSecret() ? "Secret[****]" : e.getValue());
    }
    return sb.append(']').toString();
  }
}
