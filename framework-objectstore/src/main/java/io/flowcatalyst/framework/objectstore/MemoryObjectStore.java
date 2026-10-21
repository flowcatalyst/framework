package io.flowcatalyst.framework.objectstore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-process values: lost when the JVM stops, so for tests and for throwaway use. */
final class MemoryObjectStore implements ObjectStore {

  private final String name;
  private final int maxBytes;
  private final ConcurrentHashMap<String, byte[]> values = new ConcurrentHashMap<>();

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
    ObjectKeys.check(key);
    ObjectStoreFactories.checkSize(content.length, maxBytes);
    values.put(key, content.clone());
  }

  @Override
  public Optional<byte[]> get(String key) {
    ObjectKeys.check(key);
    byte[] v = values.get(key);
    return v == null ? Optional.empty() : Optional.of(v.clone());
  }

  @Override
  public void delete(String key) {
    ObjectKeys.check(key);
    values.remove(key);
  }
}
