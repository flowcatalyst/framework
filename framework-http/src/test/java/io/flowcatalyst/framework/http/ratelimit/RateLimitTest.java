package io.flowcatalyst.framework.http.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The memory limiter, the middleware and the key resolvers (rate-limiting contract). */
class RateLimitTest {

  @TempDir Path dir;

  final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);
  final AtomicInteger handled = new AtomicInteger();

  MemoryRateLimiter limiter(int limit, Duration period, int burst) {
    return MemoryRateLimiter.create("api", new RateLimitPolicy(limit, period, burst), 100_000, nanos::get);
  }

  void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  TestClient client(Middleware<Request, Response> limit) {
    Routes routes = new Routes();
    routes.get("/x", r -> { handled.incrementAndGet(); return Response.text(200, "ok"); }, RouteOptions.none().with(limit));
    return TestClient.of(routes);
  }

  // ---- the algorithm -----------------------------------------------------------------------

  @Test
  void aBurstIsAllowedThenRequestsAreLimitedUntilBudgetComesBack() {
    MemoryRateLimiter l = limiter(10, Duration.ofSeconds(10), 3); // one request per second, burst 3
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Allowed(2, Duration.ofSeconds(1)));
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Allowed(1, Duration.ofSeconds(2)));
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Allowed(0, Duration.ofSeconds(3)));
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Limited(Duration.ofSeconds(1), Duration.ofSeconds(3)));
    advance(Duration.ofMillis(999));
    assertThat(l.acquire("k", 1)).isInstanceOf(Decision.Limited.class);
    advance(Duration.ofMillis(1));
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Allowed(0, Duration.ofSeconds(3)));
    advance(Duration.ofSeconds(10));
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Allowed(2, Duration.ofSeconds(1)));
    // keys are separate
    assertThat(l.acquire("other", 1)).isInstanceOf(Decision.Allowed.class);
  }

  @Test
  void aCostedRequestUsesSeveralUnitsAndAFailedOneUsesNone() {
    MemoryRateLimiter l = limiter(10, Duration.ofSeconds(10), 5);
    assertThat(l.acquire("k", 4)).isEqualTo(new Decision.Allowed(1, Duration.ofSeconds(4)));
    assertThat(l.acquire("k", 2)).isInstanceOf(Decision.Limited.class);
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Allowed(0, Duration.ofSeconds(5)));
    assertThatIllegalArgumentException().isThrownBy(() -> l.acquire("k", 0));
    assertThatIllegalArgumentException().isThrownBy(() -> l.acquire("k", 6));
  }

  @Test
  void policiesAreChecked() {
    assertThatIllegalArgumentException().isThrownBy(() -> RateLimitPolicy.of(0, Duration.ofSeconds(1)));
    assertThatIllegalArgumentException().isThrownBy(() -> RateLimitPolicy.of(1_000_001, Duration.ofDays(1)));
    assertThatIllegalArgumentException().isThrownBy(() -> RateLimitPolicy.of(10, Duration.ZERO));
    assertThatIllegalArgumentException().isThrownBy(() -> RateLimitPolicy.of(10, Duration.ofDays(2)));
    assertThatIllegalArgumentException().isThrownBy(() -> RateLimitPolicy.of(10_000, Duration.ofMillis(1)));
    assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitPolicy(10, Duration.ofSeconds(1), 0));
    assertThat(RateLimitPolicy.of(5, Duration.ofSeconds(1)).burst()).isEqualTo(5);
  }

  @Test
  void concurrentRequestsOnOneKeyNeverExceedTheBurst() throws Exception {
    MemoryRateLimiter l = limiter(1, Duration.ofDays(1), 10);
    AtomicInteger allowed = new AtomicInteger();
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      go.await();
                    } catch (InterruptedException e) {
                      return;
                    }
                    if (l.acquire("hot", 1) instanceof Decision.Allowed) {
                      allowed.incrementAndGet();
                    }
                  }));
    }
    go.countDown();
    for (Thread t : threads) {
      t.join();
    }
    assertThat(allowed).hasValue(10);
  }

  @Test
  void aFloodOfKeysNeitherGrowsTheMapNorResetsExistingKeys() {
    MemoryRateLimiter l = MemoryRateLimiter.create("api", new RateLimitPolicy(1, Duration.ofMinutes(1), 1), 3, nanos::get);
    assertThat(l.acquire("victim", 1)).isInstanceOf(Decision.Allowed.class);
    assertThat(l.acquire("a", 1)).isInstanceOf(Decision.Allowed.class);
    assertThat(l.acquire("b", 1)).isInstanceOf(Decision.Allowed.class);
    // full: new keys share one overflow bucket (one allowed between them), the victim stays limited
    assertThat(l.acquire("new-1", 1)).isInstanceOf(Decision.Allowed.class);
    for (int i = 2; i < 1000; i++) {
      assertThat(l.acquire("new-" + i, 1)).isInstanceOf(Decision.Limited.class);
    }
    assertThat(l.acquire("victim", 1)).isInstanceOf(Decision.Limited.class);
    assertThat(l.size()).isLessThanOrEqualTo(4);
    // once buckets are full again they are purged and new keys get their own
    advance(Duration.ofMinutes(2));
    assertThat(l.acquire("fresh", 1)).isInstanceOf(Decision.Allowed.class);
    assertThat(l.acquire("fresh", 1)).isInstanceOf(Decision.Limited.class);
    assertThat(l.size()).isLessThanOrEqualTo(4);
  }

  // ---- the middleware ----------------------------------------------------------------------

  @Test
  void aLimitedRequestIs429WithRetryAfterAndTheHandlerDoesNotRun() {
    Metrics metrics = Metrics.memory(100, nanos::get);
    TestClient c = client(RateLimit.by(limiter(10, Duration.ofSeconds(10), 2), KeyResolvers.clientAddress()).metrics(metrics).build());
    TestResponse first = c.get("/x").send();
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.header("RateLimit-Limit")).isEqualTo("2");
    assertThat(first.header("RateLimit-Remaining")).isEqualTo("1");
    assertThat(first.header("RateLimit-Reset")).isEqualTo("1");
    assertThat(c.get("/x").send().status()).isEqualTo(200);
    TestResponse limited = c.get("/x").send();
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.header("Retry-After")).isEqualTo("1");
    assertThat(limited.header("RateLimit-Remaining")).isEqualTo("0");
    assertThat(limited.body()).isEqualTo("{\"error\":\"RATE_LIMITED\",\"message\":\"too many requests\"}");
    assertThat(handled).hasValue(2);
    // another address has its own budget
    assertThat(c.get("/x").remoteAddress("10.0.0.2").send().status()).isEqualTo(200);
    double counted =
        metrics.snapshot().families().stream()
            .filter(f -> f.name().equals("fw_http_rate_limited_total"))
            .flatMap(f -> f.samples().stream())
            .mapToDouble(MetricsSnapshot.Sample::value)
            .sum();
    assertThat(counted).isEqualTo(1.0);
  }

  @Test
  void retryAfterIsRoundedUpToWholeSecondsAndHeadersCanBeOff() {
    TestClient c = client(RateLimit.by(limiter(1, Duration.ofMillis(2500), 1), KeyResolvers.clientAddress()).headers(false).build());
    assertThat(c.get("/x").send().header("RateLimit-Limit")).isNull();
    TestResponse limited = c.get("/x").send();
    assertThat(limited.header("Retry-After")).isEqualTo("3");
    assertThat(limited.header("RateLimit-Limit")).isNull();
  }

  @Test
  void requestsWithoutKeyMaterialShareOneBucketInsteadOfEscapingTheLimit() {
    TestClient c = client(RateLimit.by(limiter(1, Duration.ofMinutes(1), 1), KeyResolvers.header("X-Api-Key")).build());
    assertThat(c.get("/x").send().status()).isEqualTo(200);
    assertThat(c.get("/x").send().status()).isEqualTo(429);
    assertThat(c.get("/x").header("X-Api-Key", "").send().status()).isEqualTo(429);
    assertThat(c.get("/x").header("X-Api-Key", "k1").send().status()).isEqualTo(200);
    assertThat(c.get("/x").header("X-Api-Key", "k1").send().status()).isEqualTo(429);
  }

  @Test
  void storeFailuresFollowThePolicy() {
    RateLimiter broken =
        new RateLimiter() {
          @Override
          public String name() {
            return "broken";
          }

          @Override
          public RateLimitPolicy policy() {
            return RateLimitPolicy.of(1, Duration.ofSeconds(1));
          }

          @Override
          public Decision acquire(String key, int cost) {
            throw new RateLimitStoreException("down", new IOException("refused"));
          }
        };
    assertThat(client(RateLimit.by(broken, KeyResolvers.clientAddress()).build()).get("/x").send().status()).isEqualTo(200);
    TestResponse denied =
        client(RateLimit.by(broken, KeyResolvers.clientAddress()).onStoreFailure(RateLimit.OnStoreFailure.DENY).build())
            .get("/x")
            .send();
    assertThat(denied.status()).isEqualTo(503);
    assertThat(handled).hasValue(1);
  }

  @Test
  void longKeysAreHashed() {
    assertThat(RateLimit.key(r -> "k".repeat(500), null)).startsWith("sha256:").hasSize(7 + 64);
    assertThat(RateLimit.key(r -> null, null)).isEqualTo("-");
    assertThat(RateLimit.key(r -> "short", null)).isEqualTo("short");
  }

  // ---- key resolvers -----------------------------------------------------------------------

  String keyOf(KeyResolver resolver, String remote, Map<String, List<String>> headers) {
    String[] seen = new String[1];
    Routes routes = new Routes();
    routes.get("/k", r -> { seen[0] = String.valueOf(resolver.key(r)); return Response.noContent(); });
    TestClient.Call call = TestClient.of(routes).get("/k").remoteAddress(remote);
    headers.forEach((name, values) -> values.forEach(v -> call.header(name, v)));
    call.send();
    return seen[0];
  }

  @Test
  void clientAddressNeverReadsForwardingHeaders() {
    assertThat(keyOf(KeyResolvers.clientAddress(), "203.0.113.7", Map.of("X-Forwarded-For", List.of("198.51.100.1"))))
        .isEqualTo("ip:203.0.113.7");
    assertThat(keyOf(KeyResolvers.clientAddress(), null, Map.of())).isEqualTo("null");
  }

  @Test
  void forwardedForTakesOnlyTheEntryTheTrustedProxiesWrote() {
    KeyResolver one = KeyResolvers.forwardedFor(1);
    // the caller wrote "1.1.1.1"; the load balancer appended the address it saw
    assertThat(keyOf(one, "10.0.0.1", Map.of("X-Forwarded-For", List.of("1.1.1.1, 203.0.113.9")))).isEqualTo("ip:203.0.113.9");
    // several header lines are one list
    assertThat(keyOf(one, "10.0.0.1", Map.of("X-Forwarded-For", List.of("1.1.1.1", "203.0.113.9")))).isEqualTo("ip:203.0.113.9");
    KeyResolver two = KeyResolvers.forwardedFor(2);
    assertThat(keyOf(two, "10.0.0.1", Map.of("X-Forwarded-For", List.of("6.6.6.6, 203.0.113.9, 10.0.0.5")))).isEqualTo("ip:203.0.113.9");
    // too few entries, or garbage, or a name (never resolved): the peer address
    assertThat(keyOf(two, "10.0.0.1", Map.of("X-Forwarded-For", List.of("203.0.113.9")))).isEqualTo("ip:10.0.0.1");
    assertThat(keyOf(one, "10.0.0.1", Map.of("X-Forwarded-For", List.of("not-an-ip")))).isEqualTo("ip:10.0.0.1");
    assertThat(keyOf(one, "10.0.0.1", Map.of("X-Forwarded-For", List.of("localhost")))).isEqualTo("ip:10.0.0.1");
    assertThat(keyOf(one, "10.0.0.1", Map.of())).isEqualTo("ip:10.0.0.1");
    assertThatIllegalArgumentException().isThrownBy(() -> KeyResolvers.forwardedFor(0));
  }

  @Test
  void ipv6AddressesAreGroupedByTheirSlash64() {
    String a = keyOf(KeyResolvers.clientAddress(), "2001:db8:1:2:aaaa::1", Map.of());
    String b = keyOf(KeyResolvers.clientAddress(), "2001:db8:1:2:bbbb:cccc:dddd:eeee", Map.of());
    String c = keyOf(KeyResolvers.clientAddress(), "2001:db8:1:3::1", Map.of());
    assertThat(a).isEqualTo(b).isEqualTo("ip6:20010db800010002::/64");
    assertThat(c).isNotEqualTo(a);
    assertThat(keyOf(KeyResolvers.forwardedFor(1), "10.0.0.1", Map.of("X-Forwarded-For", List.of("[2001:db8:1:2::9]")))).isEqualTo(a);
  }

  @Test
  void headerValuesAreHashedNeverStored() {
    String key = keyOf(KeyResolvers.header("X-Api-Key"), "10.0.0.1", Map.of("X-Api-Key", List.of("sk_live_secret")));
    assertThat(key).startsWith("h:").doesNotContain("secret").hasSize(2 + 64);
  }

  @Test
  void clientAndPrincipalNeedARouteContext() {
    String[] seen = new String[2];
    Middleware<Request, Response> capture =
        (r, next) -> {
          seen[0] = KeyResolvers.client().key(r);
          seen[1] = KeyResolvers.principal().key(r);
          return next.handle(r);
        };
    Routes routes = new Routes();
    routes.get("/c", r -> Response.noContent(), RouteOptions.requiresContext().with(capture));
    TestClient.of(routes, r -> Context.of(new ClientId("acme")).withPrincipal(new PrincipalId("u1"))).get("/c").send();
    assertThat(seen).containsExactly("c:acme", "p:u1");

    Routes plain = new Routes();
    plain.get("/p", r -> Response.noContent(), RouteOptions.none().with(capture));
    TestClient.of(plain).get("/p").send();
    assertThat(seen).containsExactly(null, null);
  }

  // ---- per-request policies (contract section 3a) -------------------------------------------

  @Test
  void aRaisedPolicyAppliesFromTheKeysNextRequest() {
    MemoryRateLimiter l = limiter(10, Duration.ofSeconds(10), 3); // T 1 s, window 3 s
    for (int i = 0; i < 3; i++) {
      assertThat(l.acquire("k", 1)).isInstanceOf(Decision.Allowed.class);
    }
    // the default policy would make the caller wait 1 s; 10 per second, burst 30 (T 100 ms, window 3 s)
    RateLimitPolicy raised = new RateLimitPolicy(10, Duration.ofSeconds(1), 30);
    assertThat(l.acquire("k", 1, raised)).isEqualTo(new Decision.Limited(Duration.ofMillis(100), Duration.ofSeconds(3)));
    advance(Duration.ofMillis(100));
    assertThat(l.acquire("k", 1, raised)).isEqualTo(new Decision.Allowed(0, Duration.ofSeconds(3)));
    advance(Duration.ofSeconds(1));
    assertThat(l.acquire("k", 1, raised)).isEqualTo(new Decision.Allowed(9, Duration.ofMillis(2100)));
    // back on the default policy, the same stored state decides
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Limited(Duration.ofMillis(100), Duration.ofMillis(2100)));
  }

  @Test
  void aRaisedRateWithASmallerWindowCapsTheDebtAtAnEmptyBucket() {
    MemoryRateLimiter l = limiter(10, Duration.ofSeconds(10), 3);
    for (int i = 0; i < 3; i++) {
      l.acquire("k", 1);
    }
    // 10 per second, burst 10: window 1 s; the 3 s debt is capped, retry after one new interval
    // (without the cap: 2.1 s)
    RateLimitPolicy faster = new RateLimitPolicy(10, Duration.ofSeconds(1), 10);
    assertThat(l.acquire("k", 1, faster)).isEqualTo(new Decision.Limited(Duration.ofMillis(100), Duration.ofSeconds(1)));
  }

  @Test
  void aLoweredPolicyCarriesUsageOverInProportionAndCapsItAtAnEmptyBucket() {
    MemoryRateLimiter l = limiter(100, Duration.ofSeconds(10), 100); // T 100 ms, window 10 s
    assertThat(l.acquire("within", 50)).isInstanceOf(Decision.Allowed.class); // debt 5 s
    assertThat(l.acquire("beyond", 50)).isInstanceOf(Decision.Allowed.class);
    // 1 per second, burst 10 (window 10 s): half the window was used, so 5 of 10, 4 left after this
    assertThat(l.acquire("within", 1, new RateLimitPolicy(1, Duration.ofSeconds(1), 10)))
        .isEqualTo(new Decision.Allowed(4, Duration.ofSeconds(6)));
    // 1 per second, burst 2 (window 2 s): the 5 s debt is beyond it, capped to empty; the wait is
    // one new interval, not the 4 s the old debt would impose
    RateLimitPolicy lower = new RateLimitPolicy(1, Duration.ofSeconds(1), 2);
    assertThat(l.acquire("beyond", 1, lower)).isEqualTo(new Decision.Limited(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    // the capped value was written back, so the reported retry holds
    advance(Duration.ofSeconds(1));
    assertThat(l.acquire("beyond", 1, lower)).isEqualTo(new Decision.Allowed(0, Duration.ofSeconds(2)));
    // from here the new policy holds
    assertThat(l.acquire("beyond", 1, lower)).isEqualTo(new Decision.Limited(Duration.ofSeconds(1), Duration.ofSeconds(2)));
    assertThatIllegalArgumentException().isThrownBy(() -> l.acquire("beyond", 3, lower));
  }

  @Test
  void aWindowBeyondSeventyThreeYearsIsRefusedNotOverflowed() {
    // 1 per day with a burst of 1 000 000: burst * T is beyond a long in nanoseconds
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new RateLimitPolicy(1, Duration.ofDays(1), 1_000_000))
        .withMessageContaining("73 years");
    MemoryRateLimiter l = limiter(1, Duration.ofDays(1), 26_000); // 71 years
    assertThat(l.acquire("k", 1)).isEqualTo(new Decision.Allowed(25_999, Duration.ofDays(1)));
  }

  final Map<String, RateLimitPolicy> plane = new ConcurrentHashMap<>();

  /** A control plane: the policy per ingestion, read from memory on every request. */
  PolicyResolver fromPlane() {
    return r -> {
      String ingestion = r.header("X-Ingestion");
      if ("boom".equals(ingestion)) {
        throw new IllegalStateException("control plane cache not loaded");
      }
      return ingestion == null ? null : plane.get(ingestion);
    };
  }

  TestResponse send(TestClient c, String ingestion) {
    return c.get("/x").header("X-Ingestion", ingestion).send();
  }

  double counter(Metrics metrics, String name) {
    return metrics.snapshot().families().stream()
        .filter(f -> f.name().equals(name))
        .flatMap(f -> f.samples().stream())
        .mapToDouble(MetricsSnapshot.Sample::value)
        .sum();
  }

  @Test
  void theResolversPolicyDecidesEachRequestAndTheHeadersReportIt() {
    Metrics metrics = Metrics.memory(100, nanos::get);
    plane.put("a", new RateLimitPolicy(10, Duration.ofMinutes(1), 3));
    TestClient c =
        client(
            RateLimit.by(limiter(1, Duration.ofMinutes(1), 1), KeyResolvers.header("X-Ingestion"))
                .policy(fromPlane())
                .metrics(metrics)
                .build());
    for (int remaining = 2; remaining >= 0; remaining--) {
      TestResponse r = send(c, "a");
      assertThat(r.status()).isEqualTo(200);
      assertThat(r.header("RateLimit-Limit")).isEqualTo("3");
      assertThat(r.header("RateLimit-Remaining")).isEqualTo(Integer.toString(remaining));
    }
    TestResponse limited = send(c, "a");
    assertThat(limited.status()).isEqualTo(429);
    assertThat(limited.header("RateLimit-Limit")).isEqualTo("3");
    // the control plane raises the ingestion's burst while the application runs
    plane.put("a", new RateLimitPolicy(10, Duration.ofMinutes(1), 5));
    TestResponse raised = send(c, "a");
    assertThat(raised.status()).isEqualTo(200);
    assertThat(raised.header("RateLimit-Limit")).isEqualTo("5");
    assertThat(raised.header("RateLimit-Remaining")).isEqualTo("1");
    // an ingestion the plane does not know (null): the limiter's default policy, never unlimited
    TestResponse unknown = send(c, "b");
    assertThat(unknown.status()).isEqualTo(200);
    assertThat(unknown.header("RateLimit-Limit")).isEqualTo("1");
    assertThat(send(c, "b").status()).isEqualTo(429);
    assertThat(counter(metrics, "fw_http_rate_limit_policy_failures_total")).isZero();
  }

  @Test
  void aResolverThatThrowsIsCountedAndDecidedWithTheDefaultPolicy() {
    Metrics metrics = Metrics.memory(100, nanos::get);
    TestClient c =
        client(
            RateLimit.by(limiter(1, Duration.ofMinutes(1), 1), KeyResolvers.header("X-Ingestion"))
                .policy(fromPlane())
                .metrics(metrics)
                .build());
    TestResponse first = send(c, "boom");
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.header("RateLimit-Limit")).isEqualTo("1");
    assertThat(send(c, "boom").status()).isEqualTo(429);
    assertThat(counter(metrics, "fw_http_rate_limit_policy_failures_total")).isEqualTo(2.0);
    assertThat(handled).hasValue(1);
  }

  @Test
  void aResolverFailureIs503WhenTheMiddlewareDenies() {
    Metrics metrics = Metrics.memory(100, nanos::get);
    TestClient c =
        client(
            RateLimit.by(limiter(1, Duration.ofMinutes(1), 1), KeyResolvers.header("X-Ingestion"))
                .policy(fromPlane())
                .onStoreFailure(RateLimit.OnStoreFailure.DENY)
                .metrics(metrics)
                .build());
    TestResponse denied = send(c, "boom");
    assertThat(denied.status()).isEqualTo(503);
    assertThat(denied.body()).contains("UNAVAILABLE");
    assertThat(handled).hasValue(0);
    assertThat(counter(metrics, "fw_http_rate_limit_policy_failures_total")).isEqualTo(1.0);
  }

  @Test
  void aPolicyWhoseBurstIsBelowTheCostIsAPolicyFailure() {
    Metrics metrics = Metrics.memory(100, nanos::get);
    plane.put("tiny", new RateLimitPolicy(10, Duration.ofMinutes(1), 1));
    TestClient c =
        client(
            RateLimit.by(limiter(10, Duration.ofMinutes(1), 5), KeyResolvers.header("X-Ingestion"))
                .cost(2)
                .policy(fromPlane())
                .metrics(metrics)
                .build());
    TestResponse r = send(c, "tiny");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("RateLimit-Limit")).isEqualTo("5");
    assertThat(counter(metrics, "fw_http_rate_limit_policy_failures_total")).isEqualTo(1.0);
  }

  @Test
  void aLimiterWithoutPerRequestPoliciesIsRefusedWhenTheMiddlewareIsBuilt() {
    RateLimitPolicy own = RateLimitPolicy.of(5, Duration.ofSeconds(1));
    RateLimiter legacy =
        new RateLimiter() {
          @Override
          public String name() {
            return "legacy";
          }

          @Override
          public RateLimitPolicy policy() {
            return own;
          }

          @Override
          public Decision acquire(String key, int cost) {
            return new Decision.Allowed(4, Duration.ofSeconds(1));
          }
        };
    assertThat(legacy.supportsPerRequestPolicy()).isFalse();
    assertThat(legacy.acquire("k", 1, own)).isEqualTo(new Decision.Allowed(4, Duration.ofSeconds(1)));
    assertThatThrownBy(() -> legacy.acquire("k", 1, RateLimitPolicy.of(6, Duration.ofSeconds(1))))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> RateLimit.by(legacy, KeyResolvers.clientAddress()).policy(r -> null))
        .withMessageContaining("legacy");
    assertThat(limiter(1, Duration.ofSeconds(1), 1).supportsPerRequestPolicy()).isTrue();
  }

  // ---- building limiters in code -----------------------------------------------------------

  /** The application's own module: its settings, and its limiters built from them. */
  static final class IngestModule implements Module {
    static final Setting<Integer> LIMIT = Setting.integer("ingest.rate-limit").withDefault(100);
    static final Setting<Duration> PERIOD = Setting.duration("ingest.rate-period").withDefault(Duration.ofMinutes(1));

    /** The application's limiters, held in its own wiring. */
    record Limiters(RateLimiter api) {}

    @Override
    public String id() {
      return "ingest";
    }

    @Override
    public void register(AppBuilder app) {
      app.declare(LIMIT);
      app.declare(PERIOD);
      app.provide(
          Limiters.class,
          lookup -> {
            Config config = lookup.get(Config.class);
            return new Limiters(
                MemoryRateLimiter.create("api", RateLimitPolicy.of(config.get(LIMIT), config.get(PERIOD))));
          });
    }
  }

  @Test
  void limitersAreBuiltInCodeFromTheApplicationsOwnSettings() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "ingest:\n  rate-limit: 20\n  rate-period: 10s\n");
    try (App app = App.builder().configDirectory(dir).environment(Map.of()).module(new IngestModule()).start()) {
      RateLimiter api = app.get(IngestModule.Limiters.class).api();
      assertThat(api.name()).isEqualTo("api");
      assertThat(api.policy()).isEqualTo(new RateLimitPolicy(20, Duration.ofSeconds(10), 20));
    }
    RateLimitPolicy p = RateLimitPolicy.of(5, Duration.ofSeconds(1));
    assertThat(MemoryRateLimiter.create("login_v2-x", p).name()).isEqualTo("login_v2-x");
    for (String bad : new String[] {"", "Api", "a:b", "1api", "a".repeat(64)}) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> MemoryRateLimiter.create(bad, p));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> MemoryRateLimiter.create("api", p, 0));
    assertThatIllegalArgumentException().isThrownBy(() -> MemoryRateLimiter.create("api", p, 10_000_001));
  }
}
