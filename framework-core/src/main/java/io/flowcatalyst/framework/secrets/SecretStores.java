package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.EnvironmentVariables;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.core.MonotonicClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The secret store drivers, chosen in code (secrets contract section 4; config-and-code contract
 * section 3.7). Each is a record: its values are checked when it is built, and {@code with...}
 * methods return copies.
 *
 * <pre>{@code
 * SecretsModule.builder()
 *     .store("files", SecretStores.file(Path.of("/run/secrets/clients")).withCache(CachePolicy.of(Duration.ofMinutes(1), 1000)))
 *     .store("env", SecretStores.env("CLIENT_SECRET_"))
 *     .defaultStore("files")
 *     .build()
 * }</pre>
 */
public final class SecretStores {

  private SecretStores() {}

  /**
   * The {@code env} driver: {@code prefix + E(client) + "__" + E(name)}.
   *
   * @param prefix {@code [A-Z][A-Z0-9_]*_}, at least 4 characters, not a framework component's
   * @return the store, with {@link CachePolicy#defaults()}
   * @throws IllegalArgumentException for a prefix that is not allowed
   */
  public static EnvStore env(String prefix) {
    return new EnvStore(prefix, CachePolicy.defaults());
  }

  /**
   * The {@code file} driver: {@code <directory>/<client>/<name>}.
   *
   * @param directory an absolute path; it must be a directory at start-up
   * @return the store, with a 64 KiB limit and {@link CachePolicy#defaults()}
   * @throws IllegalArgumentException for a relative path
   */
  public static FileStore file(Path directory) {
    return new FileStore(directory, 65_536, CachePolicy.defaults());
  }

  /**
   * The {@code platform} driver. Blocked: the platform has no endpoint that resolves a per-client
   * secret for an application (secrets contract section 4), so start-up fails saying so.
   *
   * @return the store
   */
  public static PlatformStore platform() {
    return new PlatformStore(CachePolicy.defaults());
  }

  /**
   * The {@code env} driver.
   *
   * @param prefix the variables' prefix
   * @param cache how found values are cached
   */
  public record EnvStore(String prefix, CachePolicy cache) implements SecretStoreFactory {

    /** Checks the prefix. */
    public EnvStore {
      EnvSecretStore.checkPrefix(prefix);
    }

    /**
     * A copy with another cache policy.
     *
     * @param value the policy
     * @return the copy
     */
    public EnvStore withCache(CachePolicy value) {
      return new EnvStore(prefix, value);
    }

    @Override
    public SecretStore create(String name, Lookup lookup) {
      return cached(new EnvSecretStore(name, prefix, lookup.get(EnvironmentVariables.class)), cache, lookup);
    }
  }

  /**
   * The {@code file} driver.
   *
   * @param directory the absolute directory holding one directory per client
   * @param maxBytes the largest file read, 1 B to 1 MiB
   * @param cache how found values are cached
   */
  public record FileStore(Path directory, int maxBytes, CachePolicy cache) implements SecretStoreFactory {

    /** Checks the path and the limit. */
    public FileStore {
      if (!directory.isAbsolute()) {
        throw new IllegalArgumentException("directory must be an absolute path, was " + directory);
      }
      if (maxBytes < 1 || maxBytes > 1_048_576) {
        throw new IllegalArgumentException("maxBytes must be 1 to 1048576, was " + maxBytes);
      }
    }

    /**
     * A copy with another file size limit.
     *
     * @param value 1 B to 1 MiB
     * @return the copy
     */
    public FileStore withMaxBytes(int value) {
      return new FileStore(directory, value, cache);
    }

    /**
     * A copy with another cache policy.
     *
     * @param value the policy
     * @return the copy
     */
    public FileStore withCache(CachePolicy value) {
      return new FileStore(directory, maxBytes, value);
    }

    @Override
    public SecretStore create(String name, Lookup lookup) {
      if (!Files.isDirectory(directory)) {
        throw new IllegalArgumentException("directory " + directory + " is not a directory");
      }
      Path root;
      try {
        root = directory.toRealPath();
      } catch (IOException e) {
        throw new IllegalArgumentException("directory cannot be resolved", e);
      }
      return cached(new FileSecretStore(name, root, maxBytes), cache, lookup);
    }
  }

  /**
   * The {@code platform} driver, blocked on the platform.
   *
   * @param cache how found values would be cached
   */
  public record PlatformStore(CachePolicy cache) implements SecretStoreFactory {

    @Override
    public SecretStore create(String name, Lookup lookup) {
      throw new IllegalStateException(SecretsModule.BLOCKED);
    }
  }

  private static SecretStore cached(SecretStore store, CachePolicy cache, Lookup lookup) {
    return cache.ttl().isZero()
        ? store
        : new CachingSecretStore(store, cache.ttl(), cache.maxEntries(), lookup.get(MonotonicClock.class));
  }
}
