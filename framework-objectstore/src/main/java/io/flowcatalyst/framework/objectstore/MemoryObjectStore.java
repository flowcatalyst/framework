package io.flowcatalyst.framework.objectstore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** In-process values: lost when the JVM stops, so for tests and for throwaway use. */
final class MemoryObjectStore implements ObjectStore {

  private final String name;
  private final int maxBytes;
  private final ConcurrentHashMap<String, StoredObject> values = new ConcurrentHashMap<>();

  MemoryObjectStore(String name, int maxBytes) {
    this.name = name;
    this.maxBytes = maxBytes;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public void put(String key, byte[] content) {
    put(key, content, StoredObject.DEFAULT_CONTENT_TYPE);
  }

  @Override
  public void put(String key, byte[] content, @Nullable String contentType) {
    ObjectKeys.check(key);
    String type = StoredObject.checkContentType(contentType);
    ObjectStoreFactories.checkSize(content.length, maxBytes);
    values.put(key, new StoredObject(content.clone(), type));
  }

  @Override
  public Optional<byte[]> get(String key) {
    return read(key).map(StoredObject::content);
  }

  @Override
  public Optional<StoredObject> read(String key) {
    ObjectKeys.check(key);
    StoredObject v = values.get(key);
    return v == null ? Optional.empty() : Optional.of(new StoredObject(v.content().clone(), v.contentType()));
  }

  @Override
  public void delete(String key) {
    ObjectKeys.check(key);
    values.remove(key);
  }
}
