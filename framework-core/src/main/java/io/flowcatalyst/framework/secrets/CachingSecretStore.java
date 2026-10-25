package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.core.MonotonicClock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches what a store found for {@code cache-ttl}, keyed by (canonical client, name), at most
 * {@code cache-max} entries, least recently used first out. "Not found" and refusals are never
 * cached, so a secret added later is seen at once (and they drop a stale entry).
 *
 * <p>Three behaviours beyond the plain cache (platform-config contract section 3):
 *
 * <ul>
 *   <li><b>Aliases.</b> The key's client is {@link SecretStore#canonical}, so a store that knows two
 *       names for one client shares one entry, and {@code evict} removes it under either name.
 *   <li><b>Single flight.</b> Concurrent misses for one key make one call to the store; the others
 *       wait for its result or its exception.
 *   <li><b>Stale on error.</b> When the store throws {@link SecretStoreException}, the last found
 *       value is served for {@code stale-if-error} past its expiry, with a WARN naming the store,
 *       client and secret (never the value). Never for "not found" or a refusal.
 * </ul>
 */
final class CachingSecretStore implements SecretStore {

  private static final Logger LOG = LoggerFactory.getLogger(CachingSecretStore.class);

  private record Key(String client, String name) {}

  private record Cached(Secret secret, long expiresAt, long staleUntil) {}

  private final SecretStore inner;
  private final long ttlNanos;
  private final long staleNanos;
  private final MonotonicClock clock;
  private final ReentrantLock lock = new ReentrantLock();
  private final LinkedHashMap<Key, Cached> entries;
  private final Map<Key, CompletableFuture<SecretResult>> inFlight = new HashMap<>();

  /** Bumped by every evict, so a lookup that started before it does not put its answer back. */
  private long epoch;

  CachingSecretStore(SecretStore inner, Duration ttl, int max, MonotonicClock clock) {
    this(inner, new CachePolicy(ttl, max, Duration.ZERO), clock);
  }

  CachingSecretStore(SecretStore inner, CachePolicy policy, MonotonicClock clock) {
    this.inner = inner;
    this.ttlNanos = policy.ttl().toNanos();
    this.staleNanos = policy.staleIfError().toNanos();
    this.clock = clock;
    int max = policy.maxEntries();
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
    Key key = new Key(inner.canonical(client).value(), name);
    CompletableFuture<SecretResult> mine = null;
    CompletableFuture<SecretResult> waitFor;
    @Nullable Cached old = null;
    long startedAt;
    lock.lock();
    try {
      Cached e = entries.get(key);
      long now = clock.nanos();
      if (e != null) {
        if (now - e.expiresAt() < 0) {
          return new SecretResult.Found(e.secret());
        }
        if (staleNanos > 0 && now - e.staleUntil() < 0) {
          old = e; // expired, kept as the fallback for a failing store
        } else {
          entries.remove(key);
        }
      }
      waitFor = inFlight.get(key);
      if (waitFor == null) {
        mine = new CompletableFuture<>();
        inFlight.put(key, mine);
      }
      startedAt = epoch;
    } finally {
      lock.unlock();
    }
    if (waitFor != null) {
      return await(waitFor);
    }
    CompletableFuture<SecretResult> flight = Objects.requireNonNull(mine);
    try {
      SecretResult result;
      try {
        result = inner.find(client, name); // outside the lock: it may do I/O
      } catch (SecretStoreException ex) {
        result = fallback(old, ex, client, name, startedAt);
      }
      remember(key, client, name, result, startedAt);
      flight.complete(result);
      return result;
    } catch (RuntimeException | Error ex) {
      flight.completeExceptionally(ex);
      throw ex;
    } finally {
      lock.lock();
      try {
        inFlight.remove(key, flight);
      } finally {
        lock.unlock();
      }
    }
  }

  /** The stale value while the store is failing, or the failure. */
  private SecretResult fallback(@Nullable Cached old, SecretStoreException ex, ClientId client, String name, long startedAt) {
    if (old != null) {
      lock.lock();
      try {
        long now = clock.nanos();
        if (epoch == startedAt && now - old.staleUntil() < 0) {
          LOG.warn(
              "secret store {} could not be read ({}); serving the last value of secret '{}' of client '{}' for up to {} s more",
              inner,
              ex.getMessage(),
              name,
              client.value(),
              Math.max(0, (old.staleUntil() - now) / 1_000_000_000L));
          return new SecretResult.Found(old.secret());
        }
      } finally {
        lock.unlock();
      }
    }
    throw ex;
  }

  /** Stores a found answer under the canonical client; drops the entry for any other answer. */
  private void remember(Key key, ClientId client, String name, SecretResult result, long startedAt) {
    if (result instanceof SecretResult.Found found) {
      Key canonical = new Key(inner.canonical(client).value(), name); // the store may have learned the alias
      lock.lock();
      try {
        if (epoch != startedAt) {
          return; // evicted meanwhile: the answer may predate the change
        }
        Cached current = entries.get(canonical);
        if (current != null && current.secret() == found.secret()) {
          return; // the stale value served on error: keep its original expiry
        }
        long now = clock.nanos();
        entries.remove(key);
        entries.put(canonical, new Cached(found.secret(), now + ttlNanos, now + ttlNanos + staleNanos));
      } finally {
        lock.unlock();
      }
    } else {
      lock.lock();
      try {
        entries.remove(key);
      } finally {
        lock.unlock();
      }
    }
  }

  private static SecretResult await(CompletableFuture<SecretResult> flight) {
    try {
      return flight.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SecretStoreException("interrupted while waiting for a secret lookup already in progress", null);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      if (cause instanceof Error err) {
        throw err;
      }
      throw new SecretStoreException("a secret lookup already in progress failed", null);
    }
  }

  @Override
  public ClientId canonical(ClientId client) {
    return inner.canonical(client);
  }

  @Override
  public void evict(ClientId client, String name) {
    ClientId canonical = inner.canonical(client);
    lock.lock();
    try {
      epoch++;
      entries.keySet().removeIf(k -> k.name().equals(name) && sameClient(k, client, canonical));
      inFlight.keySet().removeIf(k -> k.name().equals(name) && sameClient(k, client, canonical));
    } finally {
      lock.unlock();
    }
    inner.evict(client, name);
  }

  /** Whether a key is for this client under any of its names. */
  private boolean sameClient(Key k, ClientId client, ClientId canonical) {
    return k.client().equals(client.value())
        || k.client().equals(canonical.value())
        || inner.canonical(new ClientId(k.client())).equals(canonical);
  }

  @Override
  public void evictAll() {
    lock.lock();
    try {
      epoch++;
      entries.clear();
      inFlight.clear();
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
