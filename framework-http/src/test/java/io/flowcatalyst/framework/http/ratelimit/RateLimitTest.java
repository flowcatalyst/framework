package io.flowcatalyst.framework.http.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
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
    return new MemoryRateLimiter("api", new RateLimitPolicy(limit, period, burst), 100_000, nanos::get);
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
    MemoryRateLimiter l = new MemoryRateLimiter("api", new RateLimitPolicy(1, Duration.ofMinutes(1), 1), 3, nanos::get);
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

  // ---- configuration -----------------------------------------------------------------------

  @Test
  void limitersComeFromConfiguration() throws IOException {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        rate-limits:
          instances:
            api: { driver: memory, limit: 100, period: 1m, burst: 20, max-keys: 500 }
        """);
    try (App app = App.builder().configDirectory(dir).environment(Map.of()).module(new RateLimitModule()).start()) {
      RateLimiter api = app.get(RateLimiters.class).get("api");
      assertThat(api.policy()).isEqualTo(new RateLimitPolicy(100, Duration.ofMinutes(1), 20));
      assertThat(app.get(RateLimiters.class).names()).containsExactly("api");
    }
    for (String bad :
        new String[] {
          "{ driver: memory, period: 1m }", "{ driver: memory, limit: 5 }", "{ driver: memory, limit: 5, period: 1m, typo: 1 }",
          "{ driver: memory, limit: 0, period: 1m }", "{ driver: memory, limit: 5, period: 1m, max-keys: 0 }"
        }) {
      Files.writeString(dir.resolve("application.yaml"), "rate-limits:\n  instances:\n    api: " + bad + "\n");
      Throwable t =
          catchThrowable(() -> App.builder().configDirectory(dir).environment(Map.of()).module(new RateLimitModule()).start().close());
      assertThat(t).as(bad).isInstanceOf(StartupException.class);
    }
  }
}
