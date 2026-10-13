package io.flowcatalyst.framework.auth;

import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The {@code memory} store: one process only, lost on restart. For tests and single-process
 * development; use {@code database} or {@code redis} when there is more than one replica.
 */
public final class MemorySessionStore implements SessionStore {

  private record Entry(String json, Instant expiresAt) {}

  private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong creates = new java.util.concurrent.atomic.AtomicLong();
  private final InstantSource clock;

  private MemorySessionStore(InstantSource clock) {
    this.clock = clock;
  }

  /**
   * Creates an empty store.
   *
   * @param clock decides expiry; the application's {@code InstantSource}
   * @return the store
   */
  public static MemorySessionStore create(InstantSource clock) {
    return new MemorySessionStore(clock);
  }

  @Override
  public String create(String kind, String json, Instant expiresAt) {
    String handle = SessionHandles.newHandle();
    entries.put(key(kind, handle), new Entry(json, expiresAt));
    if (creates.incrementAndGet() % 1024 == 0) {
      Instant now = clock.instant();
      entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
    }
    return handle;
  }

  @Override
  public Optional<String> read(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return Optional.empty();
    }
    String key = key(kind, handle);
    Entry e = entries.get(key);
    Optional<String> found = live(e);
    if (e != null && found.isEmpty()) {
      entries.remove(key, e);
    }
    return found;
  }

  @Override
  public Optional<String> take(String kind, String handle) {
    if (!SessionHandles.isWellFormed(handle)) {
      return Optional.empty();
    }
    return live(entries.remove(key(kind, handle)));
  }

  @Override
  public String update(String kind, String handle, String json, Instant expiresAt) {
    if (SessionHandles.isWellFormed(handle)) {
      entries.computeIfPresent(key(kind, handle), (k, old) -> new Entry(json, expiresAt));
    }
    return handle;
  }

  @Override
  public void delete(String kind, String handle) {
    if (SessionHandles.isWellFormed(handle)) {
      entries.remove(key(kind, handle));
    }
  }

  /**
   * How many records are held, expired ones not yet removed included; for tests.
   *
   * @return the count
   */
  public int size() {
    return entries.size();
  }

  private Optional<String> live(@org.jspecify.annotations.Nullable Entry e) {
    if (e == null || !clock.instant().isBefore(e.expiresAt())) {
      return Optional.empty();
    }
    return Optional.of(e.json());
  }

  private static String key(String kind, String handle) {
    return kind + ":" + SessionHandles.hash(handle);
  }
}
