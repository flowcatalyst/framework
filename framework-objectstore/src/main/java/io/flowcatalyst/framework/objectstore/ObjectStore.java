package io.flowcatalyst.framework.objectstore;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A place to keep named byte values: an archive of what was applied, an uploaded file, a report. It
 * is deliberately small. A key names one value and its content type; there is no listing, no
 * other metadata and no partial read, so every driver can honour it the same way.
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
   * Stores a value, replacing any value under the key; it reads back with {@link
   * StoredObject#DEFAULT_CONTENT_TYPE}.
   *
   * @param key the key, see {@link ObjectKeys#check}
   * @param content the bytes; may be empty
   * @throws IllegalArgumentException for a key that is not allowed or content over the store's
   *     size limit
   * @throws ObjectStoreException when the store cannot be written
   */
  void put(String key, byte[] content);

  /**
   * Stores a value with its content type, replacing any value (and content type) under the key.
   * {@link #read} returns the content type; the {@code s3} driver also sets it as the object's
   * {@code Content-Type}, the {@code folder} driver keeps it in a hidden file beside the value.
   *
   * <p>The default (for an implementation written before content types) stores the bytes and drops
   * the type; the framework's drivers keep it.
   *
   * @param key the key, see {@link ObjectKeys#check}
   * @param content the bytes; may be empty
   * @param contentType the media type, see {@link StoredObject#checkContentType}; {@code null} or
   *     {@code ""} stores {@link StoredObject#DEFAULT_CONTENT_TYPE}
   * @throws IllegalArgumentException for a key or content type that is not allowed, or content over
   *     the store's size limit
   * @throws ObjectStoreException when the store cannot be written
   */
  default void put(String key, byte[] content, @Nullable String contentType) {
    StoredObject.checkContentType(contentType);
    put(key, content);
  }

  /**
   * Reads a value with its content type.
   *
   * <p>The default (for an implementation written before content types) reports {@link
   * StoredObject#DEFAULT_CONTENT_TYPE}; the framework's drivers return what was stored.
   *
   * @param key the key
   * @return the value and its content type ({@link StoredObject#DEFAULT_CONTENT_TYPE} when it was
   *     stored without one), or empty when there is no value under the key
   * @throws IllegalArgumentException for a key that is not allowed
   * @throws ObjectStoreException when the store cannot be read, or the value is over the store's
   *     size limit
   */
  default Optional<StoredObject> read(String key) {
    return get(key).map(bytes -> new StoredObject(bytes, StoredObject.DEFAULT_CONTENT_TYPE));
  }

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
