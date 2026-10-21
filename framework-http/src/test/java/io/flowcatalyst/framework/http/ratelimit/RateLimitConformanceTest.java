package io.flowcatalyst.framework.http.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/ratelimit/gcra.json} (rate-limiting contract sections 1, 2
 * and 3a: the algorithm, the policy ranges, the names, the key bound) and
 * {@code conformance/ratelimit/http.json} (sections 3 and 4: the 429 envelope, the headers, the
 * failure modes and the key resolvers). Java's memory driver writes them; Go compares.
 */
class RateLimitConformanceTest {

  static final String GCRA = "ratelimit/gcra.json";
  static final String HTTP = "ratelimit/http.json";
  static final long SECOND = 1_000_000_000L;
  static final long MILLI = 1_000_000L;

  // ---- gcra.json ---------------------------------------------------------------------------

  record P(int limit, long periodNanos, int burst) {
    RateLimitPolicy policy() {
      return new RateLimitPolicy(limit, Duration.ofNanos(periodNanos), burst);
    }

    ObjectNode json() {
      ObjectNode n = JsonVectors.mapper().createObjectNode();
      n.put("limit", limit);
      n.put("periodNanos", periodNanos);
      n.put("burst", burst);
      return n;
    }
  }

  record Step(long at, String key, int cost, P policy) {}

  record Scenario(String name, P policy, int maxKeys, List<Step> steps) {}

  static Step s(long at, String key, int cost) {
    return new Step(at, key, cost, null);
  }

  static Step s(long at, String key, int cost, P policy) {
    return new Step(at, key, cost, policy);
  }

  static List<Step> repeat(long at, String key, int cost, int times) {
    List<Step> out = new ArrayList<>();
    for (int i = 0; i < times; i++) {
      out.add(s(at, key, cost));
    }
    return out;
  }

  static List<Step> concat(List<Step>... lists) {
    List<Step> out = new ArrayList<>();
    for (List<Step> l : lists) {
      out.addAll(l);
    }
    return out;
  }

  static List<Scenario> scenarios() {
    long t0 = 1_000 * SECOND;
    List<Scenario> out = new ArrayList<>();
    P tenPer10s3 = new P(10, 10 * SECOND, 3);
    out.add(new Scenario("a fresh key, burst exhaustion and refill after exactly T", tenPer10s3, 100_000,
        List.of(s(t0, "k", 1), s(t0, "k", 1), s(t0, "k", 1), s(t0, "k", 1), s(t0 + SECOND - 1, "k", 1), s(t0 + SECOND, "k", 1), s(t0 + SECOND, "k", 1),
            s(t0 + 11 * SECOND, "k", 1), s(t0 + 11 * SECOND, "other", 1))));
    out.add(new Scenario("a full bucket again after a long idle", new P(5, SECOND, 5), 100_000,
        concat(repeat(t0, "k", 1, 5), List.of(s(t0, "k", 1), s(t0 + 100 * SECOND, "k", 1)))));
    out.add(new Scenario("costs greater than one", new P(10, 10 * SECOND, 5), 100_000,
        List.of(s(t0, "k", 3), s(t0, "k", 3), s(t0, "k", 2), s(t0, "k", 1), s(t0 + 3 * SECOND, "k", 3), s(t0 + 3 * SECOND, "k", 5),
            s(t0 + 20 * SECOND, "k", 5), s(t0 + 20 * SECOND, "k", 6), s(t0 + 20 * SECOND, "k", 0), s(t0 + 20 * SECOND, "k", -1))));
    out.add(new Scenario("a limited request stores nothing", new P(2, SECOND, 2), 100_000,
        List.of(s(t0, "k", 1), s(t0, "k", 1), s(t0, "k", 1), s(t0, "k", 1), s(t0 + 500 * MILLI, "k", 1), s(t0 + 500 * MILLI, "k", 1))));
    out.add(new Scenario("burst below the limit", new P(100, 60 * SECOND, 1), 100_000,
        List.of(s(t0, "k", 1), s(t0, "k", 1), s(t0 + 599 * MILLI, "k", 1), s(t0 + 600 * MILLI, "k", 1))));
    out.add(new Scenario("burst above the limit", new P(2, SECOND, 10), 100_000,
        concat(repeat(t0, "k", 1, 11), List.of(s(t0 + 500 * MILLI, "k", 1)))));
    out.add(new Scenario("the smallest interval, one microsecond", new P(1_000_000, SECOND, 1_000_000), 100_000,
        List.of(s(t0, "k", 1), s(t0, "k", 1_000_000), s(t0 + 1_000, "k", 1), s(t0 + 1_000, "k", 1))));
    out.add(new Scenario("a long period", new P(1, 86_400 * SECOND, 1), 100_000,
        List.of(s(t0, "k", 1), s(t0 + 86_399 * SECOND, "k", 1), s(t0 + 86_400 * SECOND, "k", 1))));
    // per-request policies (section 3a)
    P tenPer10sB3 = new P(10, 10 * SECOND, 3);
    out.add(new Scenario("per-request policy raised: the debt is kept and measured against the new policy", tenPer10sB3, 100_000,
        List.of(s(t0, "k", 1), s(t0, "k", 1), s(t0, "k", 1), s(t0, "k", 1, new P(10, SECOND, 30)), s(t0 + 99 * MILLI, "k", 1, new P(10, SECOND, 30)),
            s(t0 + 100 * MILLI, "k", 1, new P(10, SECOND, 30)))));
    P hundredPer10s = new P(100, 10 * SECOND, 100);
    out.add(new Scenario("per-request policy lowered beyond the window: capped to empty, retry after one interval", hundredPer10s, 100_000,
        List.of(s(t0, "k", 50), s(t0, "k", 1, new P(1, SECOND, 2)), s(t0 + SECOND - 1, "k", 1, new P(1, SECOND, 2)), s(t0 + SECOND, "k", 1, new P(1, SECOND, 2)))));
    out.add(new Scenario("per-request policy lowered within the window: the debt is kept in proportion", hundredPer10s, 100_000,
        List.of(s(t0, "k", 50), s(t0, "k", 1, new P(1, SECOND, 10)), s(t0, "k", 4, new P(1, SECOND, 10)), s(t0, "k", 1, new P(1, SECOND, 10)))));
    out.add(new Scenario("per-request policy: a limited request writes the cap back", hundredPer10s, 100_000,
        List.of(s(t0, "k", 100), s(t0, "k", 2, new P(1, SECOND, 2)), s(t0 + SECOND, "k", 1, new P(1, SECOND, 2)), s(t0 + SECOND, "k", 2, new P(1, SECOND, 2)),
            s(t0 + 2 * SECOND, "k", 2, new P(1, SECOND, 2)))));
    out.add(new Scenario("per-request policy: a cost above the policy's burst is refused", tenPer10sB3, 100_000,
        List.of(s(t0, "k", 3, new P(10, SECOND, 2)), s(t0, "k", 2, new P(10, SECOND, 2)))));
    out.add(new Scenario("per-request policy: alternating policies share one TAT", tenPer10sB3, 100_000,
        List.of(s(t0, "k", 1), s(t0, "k", 1, new P(1, SECOND, 1)), s(t0 + SECOND, "k", 1, new P(1, SECOND, 1)), s(t0 + SECOND, "k", 1))));
    // bounded memory
    out.add(new Scenario("a full table shares one overflow bucket", new P(1, 10 * SECOND, 1), 2,
        List.of(s(t0, "k1", 1), s(t0, "k2", 1), s(t0, "k3", 1), s(t0, "k4", 1), s(t0, "k1", 1), s(t0, "k5", 1))));
    out.add(new Scenario("a full table purges buckets that are full again before overflowing", new P(1, 10 * SECOND, 1), 2,
        List.of(s(t0, "k1", 1), s(t0, "k2", 1), s(t0 + 20 * SECOND, "k3", 1), s(t0 + 20 * SECOND, "k4", 1), s(t0 + 20 * SECOND, "k5", 1))));
    return out;
  }

  /** Policies to build: accepted or refused. */
  static List<P> policyChecks() {
    long day = 86_400 * SECOND;
    List<P> out = new ArrayList<>();
    out.add(new P(1, SECOND, 1));
    out.add(new P(0, SECOND, 1));
    out.add(new P(-1, SECOND, 1));
    out.add(new P(1, SECOND, 0));
    out.add(new P(1_000_000, SECOND, 1));
    out.add(new P(1_000_001, SECOND, 1));
    out.add(new P(1, SECOND, 1_000_000));
    out.add(new P(1, SECOND, 1_000_001));
    out.add(new P(1_000_000, SECOND, 1_000_000));
    out.add(new P(1, 0, 1));
    out.add(new P(1, 999_999, 1));
    out.add(new P(1, MILLI, 1));
    out.add(new P(1, day, 1));
    out.add(new P(1, day + 1, 1));
    out.add(new P(1000, MILLI, 1));
    out.add(new P(1001, MILLI, 1));
    out.add(new P(1000, MILLI + 999, 1));
    out.add(new P(1, day, 26_688));
    out.add(new P(1, day, 26_689));
    out.add(new P(1, day, 1_000_000));
    out.add(new P(1, 3_599_999_999_999L, 1));
    return out;
  }

  static final List<String> NAMES =
      List.of("a", "api", "login-v2", "a_b", "a-b_c9", "x".repeat(63), "x".repeat(64), "", "A", "Api", "1a", "a.b", "a b", "-a", "_a", "a:b", "é");

  @Test
  void theGcraVectorMatchesTheMemoryDriver() {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "GCRA rate limiting (rate-limiting contract sections 1, 2, 3a), decided by the memory driver with a pinned clock. policy"
            + " = {limit, periodNanos, burst}; T = periodNanos / limit (integer division) and the window is burst * T."
            + " scenarios: a fresh limiter with the default policy and maxKeys; each step is (at, key, cost[, policy]) in"
            + " nanoseconds on one monotonic clock (only differences matter). For cost c: tat = min(max(stored, at), at + window);"
            + " next = tat + c * T; allowed when at >= next - window, then next is stored; else limited with retryAfter ="
            + " next - window - at and tat stored only when the stored value was beyond the cap. expect: result"
            + " allowed | limited | error (IllegalArgumentException: cost outside 1..burst of the policy used), remaining ("
            + " allowed only: (at - (next - window)) / T), resetAfterNanos (allowed: next - at; limited: tat - at) and"
            + " retryAfterNanos (limited only). A key is stored per name; when the table holds maxKeys keys and the key is new,"
            + " buckets with stored <= at are purged (at most once per second of clock) and a key still not fitting shares one"
            + " overflow bucket. policyChecks: whether a policy can be built (limit and burst 1..1000000, period 1ms..1 day,"
            + " period/limit >= 1000 ns, burst * T <= 2^63-1 over 4). names: whether a limiter name is accepted"
            + " ([a-z][a-z0-9_-]{0,62}). The redis driver works in microseconds, truncating T to whole microseconds; the"
            + " vector's numbers are whole microseconds so both agree.");
    doc.put(
        "howToRun",
        "Per scenario: build a limiter with the policy, maxKeys and a clock you set to each step's 'at'; call acquire(key, cost[, policy]); compare"
            + " the decision. Regenerate from Java with -Dconformance.update=true (RateLimitConformanceTest).");
    ArrayNode scenarios = doc.putArray("scenarios");
    for (Scenario sc : scenarios()) {
      ObjectNode item = scenarios.addObject();
      item.put("name", sc.name());
      item.set("policy", sc.policy().json());
      item.put("maxKeys", sc.maxKeys());
      AtomicLong clock = new AtomicLong();
      MemoryRateLimiter limiter = MemoryRateLimiter.create("vector", sc.policy().policy(), sc.maxKeys(), clock::get);
      ArrayNode steps = item.putArray("steps");
      for (Step st : sc.steps()) {
        ObjectNode step = steps.addObject();
        step.put("at", st.at());
        step.put("key", st.key());
        step.put("cost", st.cost());
        if (st.policy() != null) {
          step.set("policy", st.policy().json());
        }
        clock.set(st.at());
        ObjectNode expect = step.putObject("expect");
        try {
          Decision d = st.policy() == null ? limiter.acquire(st.key(), st.cost()) : limiter.acquire(st.key(), st.cost(), st.policy().policy());
          switch (d) {
            case Decision.Allowed a -> {
              expect.put("result", "allowed");
              expect.put("remaining", a.remaining());
              expect.put("resetAfterNanos", a.resetAfter().toNanos());
            }
            case Decision.Limited l -> {
              expect.put("result", "limited");
              expect.put("retryAfterNanos", l.retryAfter().toNanos());
              expect.put("resetAfterNanos", l.resetAfter().toNanos());
            }
          }
        } catch (IllegalArgumentException e) {
          expect.put("result", "error");
          expect.put("exception", "IllegalArgumentException");
        }
      }
    }
    ArrayNode checks = doc.putArray("policyChecks");
    for (P p : policyChecks()) {
      ObjectNode c = checks.addObject();
      c.set("policy", p.json());
      boolean accepted;
      try {
        p.policy();
        accepted = true;
      } catch (IllegalArgumentException e) {
        accepted = false;
      }
      c.put("accepted", accepted);
    }
    ArrayNode names = doc.putArray("names");
    for (String name : NAMES) {
      ObjectNode n = names.addObject();
      n.put("name", name);
      boolean accepted;
      try {
        MemoryRateLimiter.create(name, RateLimitPolicy.of(1, Duration.ofSeconds(1)));
        accepted = true;
      } catch (IllegalArgumentException e) {
        accepted = false;
      }
      n.put("accepted", accepted);
    }
    ObjectNode defaults = doc.putObject("defaults");
    RateLimitPolicy of = RateLimitPolicy.of(7, Duration.ofSeconds(1));
    defaults.put("burstOfWithoutBurst", of.burst());
    JsonVectors.check(GCRA, doc);
  }

  // ---- http.json ---------------------------------------------------------------------------

  /** One request in a middleware case. */
  record Req(long at, String remote, List<String[]> headers) {}

  record HttpCase(
      String name,
      P policy,
      String resolver,
      int trusted,
      String headerName,
      int cost,
      boolean headers,
      String onStoreFailure,
      boolean storeDown,
      boolean withPolicyResolver,
      List<Req> requests) {}

  static Req r(long at, String remote, String... headers) {
    List<String[]> hs = new ArrayList<>();
    for (int i = 0; i < headers.length; i += 2) {
      hs.add(new String[] {headers[i], headers[i + 1]});
    }
    return new Req(at, remote, hs);
  }

  static HttpCase http(String name, P policy, List<Req> requests) {
    return new HttpCase(name, policy, "literal", 0, null, 1, true, "ALLOW", false, false, requests);
  }

  static HttpCase resolver(String name, String resolver, int trusted, String headerName, List<Req> requests) {
    return new HttpCase(name, new P(1000, SECOND, 1000), resolver, trusted, headerName, 1, true, "ALLOW", false, false, requests);
  }

  static HttpCase with(HttpCase c, int cost, boolean headers, String onStoreFailure, boolean storeDown, boolean policyResolver) {
    return new HttpCase(c.name, c.policy, c.resolver, c.trusted, c.headerName, cost, headers, onStoreFailure, storeDown, policyResolver, c.requests);
  }

  static List<HttpCase> httpCases() {
    long t0 = 1_000 * SECOND;
    P two = new P(2, 10 * SECOND, 2);
    List<HttpCase> c = new ArrayList<>();
    c.add(http("429 envelope, Retry-After rounded up and the RateLimit headers", two,
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"),
            r(t0 + 4_900 * MILLI, "10.0.0.1", "X-Test-Key", "a"), r(t0 + 5 * SECOND, "10.0.0.1", "X-Test-Key", "a"), r(t0 + 5 * SECOND, "10.0.0.1", "X-Test-Key", "a"))));
    c.add(http("Retry-After is at least one second", new P(1000, SECOND, 1),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"))));
    c.add(http("Retry-After one nanosecond over a second rounds up to two", new P(1, SECOND + 1, 1),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"))));
    c.add(http("RateLimit-Reset rounds up", new P(3, 10 * SECOND, 3),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0 + 1_500 * MILLI, "10.0.0.1", "X-Test-Key", "a"))));
    c.add(with(http("headers off: no RateLimit headers, 429 keeps Retry-After", two,
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"))), 1, false, "ALLOW", false, false));
    c.add(with(http("cost 2 uses two units", new P(10, 10 * SECOND, 5),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"), r(t0, "10.0.0.1", "X-Test-Key", "a"))), 2, true, "ALLOW", false, false));
    c.add(with(http("store failure with ALLOW lets the request through", two, List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"))), 1, true, "ALLOW", true, false));
    c.add(with(http("store failure with DENY answers 503", two, List.of(r(t0, "10.0.0.1", "X-Test-Key", "a"))), 1, true, "DENY", true, false));
    // per-request policy
    HttpCase pol = http("per-request policy: the header names the policy", new P(10, 10 * SECOND, 3),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "a", "X-Test-Policy", "1/1000/2"), r(t0, "10.0.0.1", "X-Test-Key", "a", "X-Test-Policy", "1/1000/2"),
            r(t0, "10.0.0.1", "X-Test-Key", "a", "X-Test-Policy", "1/1000/2"), r(t0, "10.0.0.1", "X-Test-Key", "a", "X-Test-Policy", "null"),
            r(t0, "10.0.0.1", "X-Test-Key", "b", "X-Test-Policy", "unlimited"), r(t0, "10.0.0.1", "X-Test-Key", "b", "X-Test-Policy", "unlimited")));
    c.add(with(pol, 1, true, "ALLOW", false, true));
    List<Req> fail =
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "c", "X-Test-Policy", "throw"), r(t0, "10.0.0.1", "X-Test-Key", "c", "X-Test-Policy", "throw"),
            r(t0, "10.0.0.1", "X-Test-Key", "c", "X-Test-Policy", "throw"), r(t0, "10.0.0.1", "X-Test-Key", "c", "X-Test-Policy", "throw"));
    c.add(with(http("per-request policy: a resolver that throws follows ALLOW (the default policy limits)", new P(10, 10 * SECOND, 3), fail), 1, true, "ALLOW", false, true));
    c.add(with(http("per-request policy: a resolver that throws follows DENY", new P(10, 10 * SECOND, 3), fail), 1, true, "DENY", false, true));
    c.add(with(http("per-request policy: a burst below the cost is a policy failure", new P(10, 10 * SECOND, 3),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "d", "X-Test-Policy", "10/1000/1"))), 2, true, "ALLOW", false, true));
    c.add(with(http("per-request policy: a burst below the cost with DENY", new P(10, 10 * SECOND, 3),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "d", "X-Test-Policy", "10/1000/1"))), 2, true, "DENY", false, true));
    // keys
    c.add(http("a missing key falls into the shared bucket", new P(1, 10 * SECOND, 1),
        List.of(r(t0, "10.0.0.1"), r(t0, "10.0.0.2"))));
    c.add(http("an empty key falls into the shared bucket", new P(1, 10 * SECOND, 1),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", ""), r(t0, "10.0.0.2", "X-Test-Key", ""))));
    c.add(http("a key of 200 characters is used as is and 201 is hashed", new P(1000, SECOND, 1000),
        List.of(r(t0, "10.0.0.1", "X-Test-Key", "k".repeat(200)), r(t0, "10.0.0.1", "X-Test-Key", "k".repeat(201)))));
    // resolvers
    c.add(resolver("clientAddress: IPv4", "clientAddress", 0, null, List.of(r(t0, "203.0.113.5"), r(t0, "10.0.0.1", "X-Forwarded-For", "9.9.9.9"))));
    c.add(resolver("clientAddress: IPv6 is grouped by /64", "clientAddress", 0, null,
        List.of(r(t0, "2001:db8:1:2:aaaa:bbbb:cccc:dddd"), r(t0, "2001:db8:1:2::1"), r(t0, "2001:db8:1:3::1"), r(t0, "[2001:db8:1:2::1]"), r(t0, "2001:DB8:1:2::1"))));
    c.add(resolver("clientAddress: no address, an unparseable address", "clientAddress", 0, null,
        List.of(r(t0, null), r(t0, ""), r(t0, "not-an-address"), r(t0, "example.com"), r(t0, "203.0.113.5:80"))));
    c.add(resolver("clientAddress: IPv4-mapped IPv6 and loopback", "clientAddress", 0, null,
        List.of(r(t0, "::ffff:203.0.113.5"), r(t0, "::1"), r(t0, "::"), r(t0, "0.0.0.0"), r(t0, "127.0.0.1"))));
    c.add(resolver("clientAddress: odd IPv4 spellings", "clientAddress", 0, null,
        List.of(r(t0, "010.001.001.001"), r(t0, "0x7f.0.0.1"), r(t0, "1.2.3"), r(t0, "256.1.1.1"), r(t0, "1.2.3.4.5"), r(t0, " 1.2.3.4"), r(t0, "2130706433"))));
    c.add(resolver("clientAddress: zone ids and long values", "clientAddress", 0, null,
        List.of(r(t0, "fe80::1%nosuchif0"), r(t0, "fe80::1%1"), r(t0, "1".repeat(65)), r(t0, "2001:db8:1:2:3:4:5:6"))));
    c.add(resolver("forwardedFor(1): the entry the proxy wrote, a spoofed left side is ignored", "forwardedFor", 1, null,
        List.of(r(t0, "10.0.0.1", "X-Forwarded-For", "203.0.113.5"), r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1, 203.0.113.5"),
            r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1, 2.2.2.2, 203.0.113.5"))));
    c.add(resolver("forwardedFor(2): the second from the right", "forwardedFor", 2, null,
        List.of(r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1, 203.0.113.5, 10.0.0.9"), r(t0, "10.0.0.1", "X-Forwarded-For", "203.0.113.5, 10.0.0.9"),
            r(t0, "10.0.0.1", "X-Forwarded-For", "10.0.0.9"))));
    c.add(resolver("forwardedFor(3): fewer entries than proxies falls back to the connection", "forwardedFor", 3, null,
        List.of(r(t0, "10.0.0.1", "X-Forwarded-For", "203.0.113.5, 10.0.0.9"), r(t0, "10.0.0.1"), r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1, 203.0.113.5, 10.0.0.9"))));
    c.add(resolver("forwardedFor(1): the chosen entry is not an IP literal", "forwardedFor", 1, null,
        List.of(r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1, unknown"), r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1, example.com"),
            r(t0, "10.0.0.1", "X-Forwarded-For", "203.0.113.5:8080"), r(t0, "10.0.0.1", "X-Forwarded-For", ""), r(t0, "10.0.0.1", "X-Forwarded-For", "1.2.3.4,"),
            r(t0, "10.0.0.1", "X-Forwarded-For", ", 1.2.3.4"))));
    c.add(resolver("forwardedFor(1): IPv6 entries, brackets, mapped IPv4, zone", "forwardedFor", 1, null,
        List.of(r(t0, "10.0.0.1", "X-Forwarded-For", "2001:db8:1:2::9"), r(t0, "10.0.0.1", "X-Forwarded-For", "[2001:db8:1:2::9]"),
            r(t0, "10.0.0.1", "X-Forwarded-For", "::ffff:203.0.113.5"), r(t0, "10.0.0.1", "X-Forwarded-For", "fe80::1%nosuchif0"))));
    c.add(resolver("forwardedFor(2): several header lines are one list in order", "forwardedFor", 2, null,
        List.of(r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1", "X-Forwarded-For", "203.0.113.5, 10.0.0.9"),
            r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1, 203.0.113.5", "X-Forwarded-For", "10.0.0.9"))));
    c.add(resolver("forwardedFor(1): spacing and case of the header name", "forwardedFor", 1, null,
        List.of(r(t0, "10.0.0.1", "x-forwarded-for", "  203.0.113.5  "), r(t0, "10.0.0.1", "X-FORWARDED-FOR", "1.1.1.1 ,\t203.0.113.6"))));
    c.add(resolver("forwardedFor: trusted proxies out of range", "forwardedFor", 0, null, List.of()));
    c.add(resolver("forwardedFor: 10 trusted proxies is the most", "forwardedFor", 10, null, List.of(r(t0, "10.0.0.1", "X-Forwarded-For", "1.1.1.1"))));
    c.add(resolver("forwardedFor: 11 trusted proxies is refused", "forwardedFor", 11, null, List.of()));
    c.add(resolver("header(name): hashed, absent and empty are the shared bucket", "header", 0, "X-Api-Key",
        List.of(r(t0, "10.0.0.1", "X-Api-Key", "secret-key-1"), r(t0, "10.0.0.1", "X-Api-Key", "secret-key-1", "X-Other", "x"), r(t0, "10.0.0.1"),
            r(t0, "10.0.0.1", "X-Api-Key", ""), r(t0, "10.0.0.1", "x-api-key", "secret-key-2"))));
    return c;
  }

  /** A limiter that records the key it was asked about and can fail. */
  static final class Recording implements RateLimiter {
    final MemoryRateLimiter inner;
    final boolean down;
    final AtomicReference<String> lastKey = new AtomicReference<>();

    Recording(MemoryRateLimiter inner, boolean down) {
      this.inner = inner;
      this.down = down;
    }

    @Override
    public String name() {
      return inner.name();
    }

    @Override
    public RateLimitPolicy policy() {
      return inner.policy();
    }

    @Override
    public Decision acquire(String key, int cost) {
      lastKey.set(key);
      if (down) {
        throw new RateLimitStoreException("store down", new RuntimeException("down"));
      }
      return inner.acquire(key, cost);
    }

    @Override
    public Decision acquire(String key, int cost, RateLimitPolicy policy) {
      lastKey.set(key);
      if (down) {
        throw new RateLimitStoreException("store down", new RuntimeException("down"));
      }
      return inner.acquire(key, cost, policy);
    }

    @Override
    public boolean supportsPerRequestPolicy() {
      return true;
    }
  }

  @Test
  void theHttpVectorMatchesTheMiddleware() {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "The rate limit middleware and key resolvers (rate-limiting contract sections 3 and 4). A route whose handler answers 200"
            + " {\"ok\":true} sits behind RateLimit.by(limiter, resolver); the limiter is the memory driver with the policy"
            + " (limit, periodNanos, burst) and the clock set to each request's 'at'. resolver: literal (the key is the"
            + " X-Test-Key header, null when absent), clientAddress, forwardedFor (trusted = n), header (name). cost, headers"
            + " (RateLimit-* on), onStoreFailure (ALLOW | DENY), storeDown (the limiter throws RateLimitStoreException) and"
            + " policyResolver (a resolver reads X-Test-Policy: 'null' -> no policy, 'throw' -> the resolver throws, 'unlimited' ->"
            + " RateLimitPolicy.UNLIMITED, 'limit/periodMillis/burst' -> that policy) are the middleware's settings. A request"
            + " is (at nanoseconds, remote address or null, headers as an ordered [name, value] list). expect per request:"
            + " status; key = the key the limiter was asked about (null when it was not asked); headers = Retry-After and"
            + " RateLimit-Limit / RateLimit-Remaining / RateLimit-Reset when present; body = the response body as parsed"
            + " JSON. A 429 is {error: RATE_LIMITED, message: 'too many requests'} with Retry-After in whole seconds rounded up"
            + " and at least 1; RateLimit-Limit is the burst of the policy used, RateLimit-Reset the seconds until the bucket is"
            + " full, rounded up. buildError: the resolver cannot be built (IllegalArgumentException). Keys are IP literals"
            + " normalised as 'ip:<address>' for IPv4 (IPv4-mapped IPv6 included) and 'ip6:<first 8 bytes in hex>::/64' for IPv6,"
            + " 'h:<sha256 hex>' for header(name); a key over 200 characters is 'sha256:<hex>'; a null or empty key is '-'."
            + " What counts as an IP literal is java.net.InetAddress.ofLiteral, which differs from other runtimes' parsers"
            + " (zone ids, leading zeros, mapped addresses): each such case records Java's answer in 'javaLiteralCase' for the"
            + " cases where a stricter parser may refuse; Go's netip is stricter and a recorded difference is expected there.");
    doc.put(
        "howToRun",
        "Per case: build the limiter and middleware as described, send each request at its time with its remote address and"
            + " headers, compare status, key, headers and body. Regenerate from Java with -Dconformance.update=true"
            + " (RateLimitConformanceTest).");
    ArrayNode cases = doc.putArray("cases");
    for (HttpCase hc : httpCases()) {
      ObjectNode item = cases.addObject();
      item.put("name", hc.name());
      item.set("policy", hc.policy().json());
      item.put("resolver", hc.resolver());
      if (hc.resolver().equals("forwardedFor")) {
        item.put("trusted", hc.trusted());
      }
      if (hc.headerName() != null) {
        item.put("headerName", hc.headerName());
      }
      item.put("cost", hc.cost());
      item.put("headers", hc.headers());
      item.put("onStoreFailure", hc.onStoreFailure());
      item.put("storeDown", hc.storeDown());
      item.put("policyResolver", hc.withPolicyResolver());
      ArrayNode reqs = item.putArray("requests");
      for (Req rq : hc.requests()) {
        ObjectNode q = reqs.addObject();
        q.put("at", rq.at());
        if (rq.remote() == null) {
          q.putNull("remote");
        } else {
          q.put("remote", rq.remote());
        }
        ArrayNode hs = q.putArray("headers");
        for (String[] h : rq.headers()) {
          hs.addArray().add(h[0]).add(h[1]);
        }
      }
      ArrayNode expects = item.putArray("expect");
      KeyResolver keyResolver;
      try {
        keyResolver =
            switch (hc.resolver()) {
              case "literal" -> request -> request.header("X-Test-Key");
              case "clientAddress" -> KeyResolvers.clientAddress();
              case "forwardedFor" -> KeyResolvers.forwardedFor(hc.trusted());
              case "header" -> KeyResolvers.header(hc.headerName());
              default -> throw new IllegalArgumentException(hc.resolver());
            };
      } catch (IllegalArgumentException e) {
        item.put("buildError", "IllegalArgumentException");
        continue;
      }
      AtomicLong clock = new AtomicLong();
      Recording limiter = new Recording(MemoryRateLimiter.create("api", hc.policy().policy(), 100_000, clock::get), hc.storeDown());
      RateLimit.Builder b =
          RateLimit.by(limiter, keyResolver)
              .cost(hc.cost())
              .headers(hc.headers())
              .onStoreFailure(RateLimit.OnStoreFailure.valueOf(hc.onStoreFailure()));
      if (hc.withPolicyResolver()) {
        b.policy(
            request -> {
              String v = request.header("X-Test-Policy");
              if (v == null || v.equals("null")) {
                return null;
              }
              if (v.equals("throw")) {
                throw new IllegalStateException("control plane down");
              }
              if (v.equals("unlimited")) {
                return RateLimitPolicy.UNLIMITED;
              }
              String[] p = v.split("/");
              return new RateLimitPolicy(Integer.parseInt(p[0]), Duration.ofMillis(Long.parseLong(p[1])), Integer.parseInt(p[2]));
            });
      }
      Middleware<Request, Response> middleware = b.build();
      Routes routes = new Routes();
      routes.get("/x", req -> Response.json(200, java.util.Map.of("ok", true)), RouteOptions.none().with(middleware));
      TestClient client = TestClient.of(routes);
      for (Req rq : hc.requests()) {
        clock.set(rq.at());
        limiter.lastKey.set(null);
        TestClient.Call call = client.get("/x");
        for (String[] h : rq.headers()) {
          call.header(h[0], h[1]);
        }
        call.remoteAddress(rq.remote());
        TestResponse resp = call.send();
        ObjectNode e = expects.addObject();
        e.put("status", resp.status());
        String key = limiter.lastKey.get();
        if (key == null) {
          e.putNull("key");
        } else {
          e.put("key", key);
        }
        ObjectNode hd = e.putObject("headers");
        for (String name : List.of("Retry-After", "RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset")) {
          String v = resp.header(name);
          if (v != null) {
            hd.put(name, v);
          }
        }
        e.set("body", resp.tree());
        if (hc.resolver().equals("clientAddress") || hc.resolver().equals("forwardedFor")) {
          e.put("javaLiteralCase", true);
        }
      }
    }
    JsonVectors.check(HTTP, doc);
    assertThat(httpCases()).isNotEmpty();
  }
}
