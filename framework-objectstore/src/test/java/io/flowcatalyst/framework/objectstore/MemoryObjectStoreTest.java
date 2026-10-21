package io.flowcatalyst.framework.objectstore;

class MemoryObjectStoreTest extends ObjectStoreContract {

  private final ObjectStore store = new MemoryObjectStore("t", 4096);

  @Override
  protected ObjectStore store() {
    return store;
  }

  @Override
  protected int limit() {
    return 4096;
  }
}
