package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.core.MonotonicClock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Caches what a store found for {@code cache-ttl}, keyed by (client, name), at most {@code
 * cache-max} entries, least recently used first out. "Not found" and refusals are never cached, so
 * a secret added later is seen at once.
 */
final class CachingSecretStore implements SecretStore {

  private record Key(String client, String name) {}

  private record Cached(Secret secret, long expiresAt) {}

  private final SecretStore inner;
  private final long ttlNanos;
  private final MonotonicClock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<Key, Cached> entries;

  CachingSecretStore(SecretStore inner, Duration ttl, int max, MonotonicClock clock) {
    this.inner = inner;
    this.ttlNanos = ttl.toNanos();
    this.clock = clock;
    this.entries =
        new LinkedHashMap<>(16, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > max;
          }
        };
  }

  @Override
  public SecretResult find(ClientId client, String name) {
    Key key = new Key(client.value(), name);
    lock.lock();
    try {
      Cached e = entries.get(key);
      if (e != null) {
        if (clock.nanos() - e.expiresAt() < 0) {
          return new SecretResult.Found(e.secret());
        }
        entries.remove(key);
      }
    } finally {
      lock.unlock();
    }
    SecretResult result = inner.find(client, name); // outside the lock: it may do I/O
    if (result instanceof SecretResult.Found found) {
      lock.lock();
      try {
        entries.put(key, new Cached(found.secret(), clock.nanos() + ttlNanos));
      } finally {
        lock.unlock();
      }
    }
    return result;
  }

  @Override
  public void evict(ClientId client, String name) {
    lock.lock();
    try {
      entries.remove(new Key(client.value(), name));
    } finally {
      lock.unlock();
    }
    inner.evict(client, name);
  }

  @Override
  public void evictAll() {
    lock.lock();
    try {
      entries.clear();
    } finally {
      lock.unlock();
    }
    inner.evictAll();
  }

  int size() {
    lock.lock();
    try {
      return entries.size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public String toString() {
    return "Cached" + inner;
  }
}
