package io.flowcatalyst.framework.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The cache's aliases, stale-on-error and single-flight behaviour (platform-config contract
 * section 3, Caching), on the injected clock and with latches instead of sleeps.
 */
class CachingSecretStoreTest {

  static final ClientId ID = new ClientId("clt_0ACME");
  static final ClientId CODE = new ClientId("acme");
  static final Duration TTL = Duration.ofSeconds(60);
  static final Duration STALE = Duration.ofMinutes(5);

  final AtomicLong now = new AtomicLong();

  /** A store that knows {@code acme} is {@code clt_0ACME} once it has answered for it. */
  static final class Aliased implements SecretStore {
    final Map<String, String> values = new ConcurrentHashMap<>();
    final Map<String, String> aliases = new ConcurrentHashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    volatile RuntimeException failure;
    volatile SecretResult fixed;
    final List<String> evicted = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public ClientId canonical(ClientId client) {
      String id = aliases.get(client.value());
      return id == null ? client : new ClientId(id);
    }

    @Override
    public SecretResult find(ClientId client, String name) {
      calls.incrementAndGet();
      if (failure != null) {
        throw failure;
      }
      if (fixed != null) {
        return fixed;
      }
      aliases.put("acme", "clt_0ACME");
      String v = values.get(name);
      return v == null ? new SecretResult.NotFound() : new SecretResult.Found(Secret.of(v));
    }

    @Override
    public void evict(ClientId client, String name) {
      evicted.add(client.value() + "/" + name);
    }
  }

  CachingSecretStore cache(SecretStore inner, Duration stale) {
    return new CachingSecretStore(inner, new CachePolicy(TTL, 100, stale), now::get);
  }

  static String reveal(SecretResult r) {
    assertThat(r).isInstanceOf(SecretResult.Found.class);
    return ((SecretResult.Found) r).secret().reveal();
  }

  // ---- aliases -------------------------------------------------------------------------------

  @Test
  void anIdentifierAndTheStoredIdShareOneEntryOnceTheStoreHasLearnedThePair() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    CachingSecretStore cache = cache(inner, STALE);

    assertThat(reveal(cache.find(CODE, "k"))).isEqualTo("v1"); // learns acme = clt_0ACME
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v1"); // same entry
    assertThat(reveal(cache.find(CODE, "k"))).isEqualTo("v1");
    assertThat(inner.calls).hasValue(1);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  void evictingUnderEitherNameRemovesTheEntryForBoth() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    CachingSecretStore cache = cache(inner, STALE);
    cache.find(CODE, "k");

    inner.values.put("k", "v2");
    cache.evict(ID, "k");
    assertThat(reveal(cache.find(CODE, "k"))).isEqualTo("v2");

    inner.values.put("k", "v3");
    cache.evict(CODE, "k");
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v3");
    assertThat(inner.evicted).containsExactly("clt_0ACME/k", "acme/k");
  }

  @Test
  void anEntryCachedBeforeThePairWasKnownIsStillEvictedByTheOtherName() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    inner.fixed = new SecretResult.Found(Secret.of("v1")); // answers without learning the alias
    CachingSecretStore cache = cache(inner, STALE);
    cache.find(CODE, "k"); // cached under "acme"
    inner.fixed = null;
    inner.aliases.put("acme", "clt_0ACME"); // learned later, from elsewhere

    inner.values.put("k", "v2");
    cache.evict(ID, "k");

    assertThat(reveal(cache.find(CODE, "k"))).isEqualTo("v2");
  }

  @Test
  void aStoreWithoutAliasesKeysOnTheClientAsGiven() {
    SecretStore plain = (client, name) -> new SecretResult.Found(Secret.of(client.value()));
    CachingSecretStore cache = cache(plain, STALE);
    assertThat(reveal(cache.find(CODE, "k"))).isEqualTo("acme");
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("clt_0ACME");
    assertThat(cache.size()).isEqualTo(2);
  }

  // ---- stale on error ------------------------------------------------------------------------

  @Test
  void aFailingStoreServesTheLastValueUntilTheStaleWindowEnds() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    CachingSecretStore cache = cache(inner, STALE);
    cache.find(ID, "k");

    now.addAndGet(TTL.toNanos()); // expired
    inner.failure = new SecretStoreException("platform down", null);
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v1");
    now.addAndGet(STALE.toNanos() - 1); // the last nanosecond of the window
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v1");
    now.addAndGet(1); // past it
    assertThatThrownBy(() -> cache.find(ID, "k")).isSameAs(inner.failure);
  }

  @Test
  void theStoreIsAskedAgainAfterTheTtlAndARecoveredAnswerReplacesTheStaleOne() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    CachingSecretStore cache = cache(inner, STALE);
    cache.find(ID, "k");
    now.addAndGet(TTL.toNanos());
    inner.failure = new SecretStoreException("down", null);
    cache.find(ID, "k");
    cache.find(ID, "k");
    assertThat(inner.calls).hasValue(3); // the store is retried on every lookup while failing

    inner.failure = null;
    inner.values.put("k", "v2");
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v2");
    inner.failure = new SecretStoreException("down again", null);
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v2"); // fresh, no call needed
    assertThat(inner.calls).hasValue(4);
  }

  @Test
  void aZeroWindowServesNothingStale() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    CachingSecretStore cache = cache(inner, Duration.ZERO);
    cache.find(ID, "k");
    now.addAndGet(TTL.toNanos());
    inner.failure = new SecretStoreException("down", null);
    assertThatThrownBy(() -> cache.find(ID, "k")).isInstanceOf(SecretStoreException.class);
  }

  @Test
  void notFoundAndRefusedNeverServeStaleAndDropTheEntry() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    CachingSecretStore cache = cache(inner, STALE);
    cache.find(ID, "k");
    now.addAndGet(TTL.toNanos());

    inner.fixed = new SecretResult.NotFound();
    assertThat(cache.find(ID, "k")).isInstanceOf(SecretResult.NotFound.class);
    assertThat(cache.size()).isZero();
    inner.fixed = null;
    inner.failure = new SecretStoreException("down", null);
    assertThatThrownBy(() -> cache.find(ID, "k")).isInstanceOf(SecretStoreException.class);

    inner.failure = null;
    cache.find(ID, "k");
    now.addAndGet(TTL.toNanos());
    inner.fixed = new SecretResult.Refused("no");
    assertThat(cache.find(ID, "k")).isInstanceOf(SecretResult.Refused.class);
    inner.fixed = null;
    inner.failure = new SecretStoreException("down", null);
    assertThatThrownBy(() -> cache.find(ID, "k")).isInstanceOf(SecretStoreException.class);
  }

  @Test
  void anEvictedEntryIsNotServedStale() {
    Aliased inner = new Aliased();
    inner.values.put("k", "v1");
    CachingSecretStore cache = cache(inner, STALE);
    cache.find(ID, "k");
    now.addAndGet(TTL.toNanos());
    cache.evict(ID, "k");
    inner.failure = new SecretStoreException("down", null);
    assertThatThrownBy(() -> cache.find(ID, "k")).isInstanceOf(SecretStoreException.class);
  }

  @Test
  void theStaleWindowIsAPolicySettingWithRangesAndADefault() {
    assertThat(CachePolicy.defaults().staleIfError()).isEqualTo(Duration.ofMinutes(5));
    assertThat(CachePolicy.of(TTL, 5).staleIfError()).isEqualTo(Duration.ofMinutes(5));
    assertThat(CachePolicy.defaults().withStaleIfError(Duration.ZERO).staleIfError()).isZero();
    assertThat(CachePolicy.none().staleIfError()).isZero();
    assertThatIllegalArgumentException().isThrownBy(() -> CachePolicy.defaults().withStaleIfError(Duration.ofMillis(-1))).withMessageContaining("staleIfError");
    assertThatIllegalArgumentException().isThrownBy(() -> CachePolicy.defaults().withStaleIfError(Duration.ofHours(25))).withMessageContaining("staleIfError");
  }

  // ---- single flight -------------------------------------------------------------------------

  /** A store whose call blocks until released, to hold concurrent lookups in flight. */
  static final class Slow implements SecretStore {
    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile RuntimeException failure;
    volatile String value = "v1";

    @Override
    public SecretResult find(ClientId client, String name) {
      calls.incrementAndGet();
      entered.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("never released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      if (failure != null) {
        throw failure;
      }
      return new SecretResult.Found(Secret.of(value));
    }
  }

  /** Starts {@code n} lookups; the first is in the store before the others start. */
  private static List<Future<SecretResult>> lookups(ExecutorService pool, CachingSecretStore cache, Slow inner, int n) throws InterruptedException {
    List<Future<SecretResult>> out = new java.util.ArrayList<>();
    out.add(pool.submit(() -> cache.find(ID, "k")));
    assertThat(inner.entered.await(10, TimeUnit.SECONDS)).isTrue();
    for (int i = 1; i < n; i++) {
      out.add(pool.submit(() -> cache.find(ID, "k")));
    }
    return out;
  }

  /** Lets the others reach the wait: they cannot finish while the store is held. */
  private static void settle(List<Future<SecretResult>> futures) throws InterruptedException {
    Thread.sleep(100);
    for (Future<SecretResult> f : futures) {
      assertThat(f.isDone()).isFalse();
    }
  }

  @Test
  void concurrentMissesForOneKeyMakeOneCallAndShareTheResult() throws Exception {
    Slow inner = new Slow();
    CachingSecretStore cache = cache(inner, STALE);
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<SecretResult>> futures = lookups(pool, cache, inner, 8);
      settle(futures);
      inner.release.countDown();
      for (Future<SecretResult> f : futures) {
        assertThat(reveal(f.get(10, TimeUnit.SECONDS))).isEqualTo("v1");
      }
    }
    assertThat(inner.calls).hasValue(1);
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v1"); // and it was cached
    assertThat(inner.calls).hasValue(1);
  }

  @Test
  void theWaitersShareTheFailureOfTheOneCallAndTheNextLookupTriesAgain() throws Exception {
    Slow inner = new Slow();
    inner.failure = new SecretStoreException("down", null);
    CachingSecretStore cache = cache(inner, STALE);
    try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
      List<Future<SecretResult>> futures = lookups(pool, cache, inner, 4);
      settle(futures);
      inner.release.countDown();
      for (Future<SecretResult> f : futures) {
        assertThatThrownBy(() -> f.get(10, TimeUnit.SECONDS)).hasCause(inner.failure);
      }
    }
    assertThat(inner.calls).hasValue(1);

    inner.failure = null;
    assertThat(reveal(cache.find(ID, "k"))).isEqualTo("v1");
    assertThat(inner.calls).hasValue(2);
  }

  @Test
  void differentKeysDoNotWaitForEachOther() throws Exception {
    Slow inner = new Slow();
    CachingSecretStore cache = cache(inner, STALE);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<SecretResult> first = pool.submit(() -> cache.find(ID, "k"));
      assertThat(inner.entered.await(10, TimeUnit.SECONDS)).isTrue();
      Future<SecretResult> second = pool.submit(() -> cache.find(ID, "other"));
      Thread.sleep(100);
      inner.release.countDown();
      reveal(first.get(10, TimeUnit.SECONDS));
      reveal(second.get(10, TimeUnit.SECONDS));
    }
    assertThat(inner.calls).hasValue(2);
  }

  @Test
  void anEvictDuringALookupKeepsItsPossiblyOldAnswerOutOfTheCache() throws Exception {
    Slow inner = new Slow();
    CachingSecretStore cache = cache(inner, STALE);
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<SecretResult> lookup = pool.submit(() -> cache.find(ID, "k"));
      assertThat(inner.entered.await(10, TimeUnit.SECONDS)).isTrue();
      cache.evict(ID, "k"); // the secret was rotated while the read was in flight
      inner.release.countDown();
      assertThat(reveal(lookup.get(10, TimeUnit.SECONDS))).isEqualTo("v1");
    }
    assertThat(cache.size()).isZero();
  }
}
