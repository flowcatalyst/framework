package io.flowcatalyst.framework.objectstore;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.core.Lookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The object store drivers of {@code framework-objectstore}, chosen in code. Each is a record: its
 * values are checked when it is built, and {@code with...} methods return copies. The S3 driver is
 * {@code S3ObjectStores.s3} in {@code framework-objectstore-s3}.
 *
 * <pre>{@code
 * ObjectStoresModule.builder().store("test", ObjectStoreFactories.memory()).build()
 * }</pre>
 */
public final class ObjectStoreFactories {

  /** The largest value a store accepts unless told otherwise: 64 MiB. */
  public static final int DEFAULT_MAX_BYTES = 64 * 1024 * 1024;

  private ObjectStoreFactories() {}

  /**
   * The {@code memory} driver: values live in this JVM only.
   *
   * @return the store, with a 64 MiB limit
   */
  public static MemoryStore memory() {
    return new MemoryStore(DEFAULT_MAX_BYTES);
  }

  /**
   * The {@code folder} driver: a value is a file under the directory. Allowed only when {@code
   * APP_ENV} is {@code local} or {@code test}; elsewhere start-up fails, because a folder on one
   * machine is not shared storage.
   *
   * @param directory an absolute path; created if missing
   * @return the store, with a 64 MiB limit
   * @throws IllegalArgumentException for a relative path
   */
  public static FolderStore folder(Path directory) {
    return new FolderStore(directory, DEFAULT_MAX_BYTES);
  }

  static void checkSize(int length, int maxBytes) {
    if (length > maxBytes) {
      throw new IllegalArgumentException("content is " + length + " bytes, over the store's limit of " + maxBytes);
    }
  }

  static int checkLimit(int maxBytes) {
    if (maxBytes < 1) {
      throw new IllegalArgumentException("maxBytes must be at least 1, was " + maxBytes);
    }
    return maxBytes;
  }

  /**
   * The {@code memory} driver.
   *
   * @param maxBytes the largest value accepted
   */
  public record MemoryStore(int maxBytes) implements ObjectStoreFactory {

    /** Checks the limit. */
    public MemoryStore {
      checkLimit(maxBytes);
    }

    /**
     * A copy with another size limit.
     *
     * @param value at least 1
     * @return the copy
     */
    public MemoryStore withMaxBytes(int value) {
      return new MemoryStore(value);
    }

    @Override
    public ObjectStore create(String name, Lookup lookup) {
      return new MemoryObjectStore(name, maxBytes);
    }
  }

  /**
   * The {@code folder} driver.
   *
   * @param directory the absolute folder holding the values
   * @param maxBytes the largest value accepted
   */
  public record FolderStore(Path directory, int maxBytes) implements ObjectStoreFactory {

    /** Checks the path and the limit. */
    public FolderStore {
      if (!directory.isAbsolute()) {
        throw new IllegalArgumentException("directory must be an absolute path, was " + directory);
      }
      checkLimit(maxBytes);
    }

    /**
     * A copy with another size limit.
     *
     * @param value at least 1
     * @return the copy
     */
    public FolderStore withMaxBytes(int value) {
      return new FolderStore(directory, value);
    }

    @Override
    public ObjectStore create(String name, Lookup lookup) {
      String environment = lookup.get(Config.class).environment();
      if (!environment.equals("local") && !environment.equals("test")) {
        throw new IllegalStateException(
            "the folder object store is allowed only when APP_ENV is local or test (it is '"
                + environment
                + "'): a folder on one machine is not shared storage; use S3 ObjectStores.s3 when deployed");
      }
      try {
        Files.createDirectories(directory);
        return new FolderObjectStore(name, directory.toRealPath(), maxBytes);
      } catch (IOException e) {
        throw new IllegalArgumentException("directory " + directory + " cannot be created or resolved", e);
      }
    }
  }
}
