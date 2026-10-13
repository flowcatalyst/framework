package io.flowcatalyst.framework.auth;

class MemorySessionStoreTest extends SessionStoreContract {

  @Override
  protected SessionStore newStore(MutableClock clock) {
    return MemorySessionStore.create(clock);
  }
}
