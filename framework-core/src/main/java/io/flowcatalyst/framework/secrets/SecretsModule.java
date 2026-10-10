package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.EnvironmentVariables;
import io.flowcatalyst.framework.config.InstanceConfig;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Instances;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The secrets module (id {@code secrets}): registers the {@code env} and {@code file} drivers, a
 * {@code platform} driver that fails start-up because the platform has no endpoint for it yet, and
 * provides {@link SecretResolver} over the configured instances.
 *
 * <pre>{@code
 * secrets:
 *   default: files
 *   instances:
 *     files: { driver: file, directory: /run/secrets/clients, cache-ttl: 5m }
 *     env:   { driver: env, prefix: CLIENT_SECRET_ }
 * }</pre>
 */
public final class SecretsModule implements Module {

  /** The module id. */
  public static final String ID = "secrets";

  /** The driver reading environment variables. */
  public static final String ENV = "env";

  /** The driver reading files under a directory. */
  public static final String FILE = "file";

  /** The platform driver; blocked until the platform offers a secret endpoint. */
  public static final String PLATFORM = "platform";

  static final String BLOCKED =
      "the platform driver is blocked on the platform: FlowCatalyst has no endpoint that resolves a"
          + " per-client secret for an application (secrets contract, section 4); use env or file";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    app.drivers()
        .register(
            SecretStore.class,
            ENV,
            (name, config, lookup, built) ->
                cached(
                    new EnvSecretStore(
                        name, config.string("prefix"), lookup.get(EnvironmentVariables.class)),
                    config,
                    lookup.get(MonotonicClock.class)));
    app.drivers()
        .register(
            SecretStore.class,
            FILE,
            (name, config, lookup, built) -> {
              Path directory = Path.of(config.string("directory"));
              if (!directory.isAbsolute() || !Files.isDirectory(directory)) {
                throw new IllegalArgumentException("directory must be an absolute path to a directory");
              }
              int maxBytes = config.integer("max-bytes", 65_536);
              if (maxBytes < 1 || maxBytes > 1_048_576) {
                throw new IllegalArgumentException("max-bytes must be 1 to 1048576");
              }
              Path root;
              try {
                root = directory.toRealPath();
              } catch (IOException e) {
                throw new IllegalArgumentException("directory cannot be resolved", e);
              }
              return cached(
                  new FileSecretStore(name, root, maxBytes), config, lookup.get(MonotonicClock.class));
            });
    app.drivers()
        .register(
            SecretStore.class,
            PLATFORM,
            (name, config, lookup, built) -> {
              throw new IllegalStateException(BLOCKED);
            });
    app.provide(
        SecretResolver.class,
        lookup -> {
          Instances<SecretStore> instances = Instances.build(SecretStore.class, "secrets", lookup);
          Map<String, SecretStore> stores = new LinkedHashMap<>(instances.all());
          return SecretResolver.of(stores, instances.defaultName().orElse(null));
        });
  }

  private static SecretStore cached(SecretStore store, InstanceConfig config, MonotonicClock clock) {
    Duration ttl = config.duration("cache-ttl", Duration.ofMinutes(5));
    int max = config.integer("cache-max", 10_000);
    if (ttl.isNegative() || ttl.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("cache-ttl must be zero to 24h");
    }
    if (max < 1 || max > 1_000_000) {
      throw new IllegalArgumentException("cache-max must be 1 to 1000000");
    }
    return ttl.isZero() ? store : new CachingSecretStore(store, ttl, max, clock);
  }
}
