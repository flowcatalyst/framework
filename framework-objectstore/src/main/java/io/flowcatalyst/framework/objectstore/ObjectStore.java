package io.flowcatalyst.framework.objectstore;

import java.util.Optional;

/**
 * A place to keep named byte values: an archive of what was applied, an uploaded file, a report. It
 * is deliberately small. A key names one value; there is no listing, no metadata and no
 * partial read, so every driver can honour it the same way.
 *
 * <p>Keys follow {@link ObjectKeys#check}: segments of letters, digits, dot, dash and underscore
 * separated by {@code /}. A key must not be both a value and the start of another key ({@code a}
 * and {@code a/b}): a folder cannot hold both, so do not rely on it with any driver.
 *
 * <p>Implementations are safe for concurrent use. A {@code put} replaces any earlier value
 * completely and a reader sees the old value or the new one, never a mixture.
 */
public interface ObjectStore {

  /**
   * The store's name in {@link ObjectStores}.
   *
   * @return the name
   */
  String name();

  /**
   * Stores a value, replacing any value under the key.
   *
   * @param key the key, see {@link ObjectKeys#check}
   * @param content the bytes; may be empty
   * @throws IllegalArgumentException for a key that is not allowed or content over the store's
   *     size limit
   * @throws ObjectStoreException when the store cannot be written
   */
  void put(String key, byte[] content);

  /**
   * Reads a value.
   *
   * @param key the key
   * @return the bytes, or empty when there is no value under the key
   * @throws IllegalArgumentException for a key that is not allowed
   * @throws ObjectStoreException when the store cannot be read, or the value is over the store's
   *     size limit
   */
  Optional<byte[]> get(String key);

  /**
   * Removes a value. Removing a value that is not there is not an error.
   *
   * @param key the key
   * @throws IllegalArgumentException for a key that is not allowed
   * @throws ObjectStoreException when the store cannot be written
   */
  void delete(String key);
}
