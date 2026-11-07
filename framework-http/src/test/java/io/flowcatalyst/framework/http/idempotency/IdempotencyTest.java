package io.flowcatalyst.framework.http.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IdempotencyTest {

  /** The caller is the X-User header (none: a context without a principal); the client is X-Client or acme. */
  private static final ContextResolver RESOLVER =
      request -> {
        String client = request.header("X-Client");
        Context c = Context.of(new ClientId(client == null ? "acme" : client));
        String user = request.header("X-User");
        return user == null ? c : c.withPrincipal(new PrincipalId(user));
      };

  private static final Duration IN_FLIGHT_TIMEOUT = Idempotency.DEFAULT_IN_FLIGHT_TIMEOUT;
  private static final Duration RETENTION = Idempotency.DEFAULT_RETENTION;

  /** A clock the test moves. */
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-07T10:00:00Z"));

  private final InstantSource clock = now::get;
  private final MemoryIdempotencyStore store = new MemoryIdempotencyStore();
  private final AtomicInteger runs = new AtomicInteger();

  /** Requests with the body "slow" wait for this latch. */
  private final CountDownLatch slowGate = new CountDownLatch(1);

  /** Counted down when a "slow" request starts. */
  private final CountDownLatch slowStarted = new CountDownLatch(1);

  private final TestClient client = clientWith(Idempotency.middleware(store, clock));

  @AfterEach
  void openTheGate() {
    slowGate.countDown();
  }

  private TestClient clientWith(Middleware<Request, Response> once) {
    Routes routes = new Routes();
    routes.post(
        "/claims",
        r -> {
          int n = runs.incrementAndGet();
          String body = r.bodyText();
          if (body.equals("fail")) {
            throw new HttpException(409, "TAKEN", "taken");
          }
          if (body.equals("boom")) {
            throw new IllegalStateException("boom");
          }
          if (body.equals("refuse")) {
            return Response.json(422, Map.of("error", "NO"));
          }
          if (body.equals("accept")) {
            return Response.empty(202);
          }
          if (body.equals("stream")) {
            return Response.stream(200, "text/plain", out -> out.write("s".getBytes(StandardCharsets.UTF_8)));
          }
          if (body.equals("slow")) {
            slowStarted.countDown();
            if (!slowGate.await(30, TimeUnit.SECONDS)) {
              throw new IllegalStateException("the test never let the slow request finish");
            }
          }
          return Response.json(201, Map.of("run", n, "body", body)).header("Location", "/claims/" + n);
        },
        RouteOptions.requiresContext().with(once));
    return TestClient.of(routes, RESOLVER);
  }

  private TestResponse post(String key, String user, String body) {
    return post(client, "/claims", key, user, body);
  }

  private static TestResponse post(TestClient client, String target, String key, String user, String body) {
    TestClient.Call call = client.post(target).body(body);
    if (user != null) {
      call.header("X-User", user);
    }
    if (key != null) {
      call.header(Idempotency.HEADER, key);
    }
    return call.send();
  }

  @Test
  void aRepeatGetsTheFirstAnswerAndTheWorkRunsOnce() {
    TestResponse first = post("k1", "u1", "pick-7");
    TestResponse again = post("k1", "u1", "pick-7");
    assertThat(runs.get()).isEqualTo(1);
    assertThat(first.status()).isEqualTo(201);
    assertThat(first.header(Idempotency.REPLAYED_HEADER)).isNull();
    assertThat(again.status()).isEqualTo(201);
    assertThat(again.body()).isEqualTo(first.body());
    assertThat(again.header("Content-Type")).isEqualTo(first.header("Content-Type"));
    assertThat(again.header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(again.header("Location")).as("only the content type is kept").isNull();
  }

  @Test
  void withoutTheHeaderEveryRequestRuns() {
    post(null, "u1", "a");
    post(null, "u1", "a");
    assertThat(runs.get()).isEqualTo(2);
  }

  @Test
  void spacesAroundTheKeyAreNotPartOfIt() {
    post("k11", "u1", "pick-7");
    TestResponse again = post("  k11 ", "u1", "pick-7");
    assertThat(again.header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void aKeyThatIsNotOneToTwoHundredPrintableAsciiCharactersSentOnceIsRefused() {
    for (String bad : new String[] {"", "  ", "a b", "kéy", "k\u0001", "k".repeat(Idempotency.MAX_KEY_LENGTH + 1)}) {
      TestResponse r = post(bad, "u1", "a");
      assertThat(r.status()).as("'%s'", bad).isEqualTo(400);
      assertThat(r.body()).as("'%s'", bad).contains("IDEMPOTENCY_KEY_INVALID");
    }
    TestResponse twice =
        client.post("/claims").header("X-User", "u1").header(Idempotency.HEADER, "k").header(Idempotency.HEADER, "k").body("a").send();
    assertThat(twice.status()).isEqualTo(400);
    assertThat(twice.body()).contains("IDEMPOTENCY_KEY_INVALID");
    assertThat(runs.get()).isZero();
    assertThat(post("k".repeat(Idempotency.MAX_KEY_LENGTH), "u1", "a").status()).isEqualTo(201);
    assertThat(post("!~\"#", "u1", "a").status()).isEqualTo(201);
    assertThat(runs.get()).isEqualTo(2);
  }

  @Test
  void anotherRequestUnderTheSameKeyIsRefused() {
    post("k2", "u1", "pick-7");
    TestResponse other = post("k2", "u1", "pick-8");
    assertThat(other.status()).isEqualTo(422);
    assertThat(other.body()).contains("IDEMPOTENCY_KEY_REUSED");
    TestResponse otherPath = post(client, "/nowhere", "k2", "u1", "pick-7");
    assertThat(otherPath.status()).isEqualTo(404);
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void theQueryStringIsPartOfTheRequest() {
    assertThat(post(client, "/claims?site=1", "kq", "u1", "pick-7").status()).isEqualTo(201);
    assertThat(post(client, "/claims?site=1", "kq", "u1", "pick-7").header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    TestResponse other = post(client, "/claims?site=2", "kq", "u1", "pick-7");
    assertThat(other.status()).isEqualTo(422);
    assertThat(other.body()).contains("IDEMPOTENCY_KEY_REUSED");
    assertThat(post(client, "/claims", "kq", "u1", "pick-7").status()).isEqualTo(422);
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void theKeyBelongsToTheCallerAndTheClient() {
    post("k3", "u1", "pick-7");
    post("k3", "u2", "pick-7");
    client.post("/claims").header("X-User", "u1").header("X-Client", "globex").header(Idempotency.HEADER, "k3").body("pick-7").send();
    assertThat(runs.get()).isEqualTo(3);
  }

  @Test
  void aRepeatWhileTheFirstRunsWaitsForItsAnswer() throws Exception {
    CompletableFuture<TestResponse> first = CompletableFuture.supplyAsync(() -> post("k4", "u1", "slow"));
    assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<TestResponse> repeat = CompletableFuture.supplyAsync(() -> post("k4", "u1", "slow"));
    Thread.sleep(150);
    assertThat(repeat).as("the repeat waits while the first runs").isNotDone();
    slowGate.countDown();
    TestResponse answer = repeat.get(10, TimeUnit.SECONDS);
    assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
    assertThat(answer.status()).isEqualTo(201);
    assertThat(answer.header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void aRepeatThatOutwaitsTheWaitIsAskedToRetry() throws Exception {
    TestClient impatient = clientWith(Idempotency.middleware(store, clock, Idempotency.Options.defaults().withMaxWait(Duration.ofMillis(100))));
    CompletableFuture<TestResponse> first = CompletableFuture.supplyAsync(() -> post(impatient, "/claims", "k12", "u1", "slow"));
    assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
    long start = System.nanoTime();
    TestResponse busy = post(impatient, "/claims", "k12", "u1", "slow");
    long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    slowGate.countDown();
    assertThat(busy.status()).isEqualTo(409);
    assertThat(busy.body()).contains("IDEMPOTENCY_IN_PROGRESS");
    assertThat(waitedMillis).as("it waited for the first").isGreaterThanOrEqualTo(100);
    assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void theTimingsAreChecked() {
    Idempotency.Options d = Idempotency.Options.defaults();
    assertThat(d).isEqualTo(new Idempotency.Options(Duration.ofHours(24), Duration.ofSeconds(10), Duration.ofMinutes(5)));
    assertThat(d.withMaxWait(Duration.ZERO).maxWait()).isZero();
    for (Runnable bad :
        new Runnable[] {
          () -> d.withRetention(Duration.ZERO), () -> d.withMaxWait(Duration.ofMillis(-1)), () -> d.withInFlightTimeout(Duration.ZERO)
        }) {
      org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy(bad::run);
    }
  }

  @Test
  void aReservationUnfinishedAfterTheInFlightTimeoutIsTakenOverAndOnlyItsOwnerCompletesIt() throws Exception {
    CompletableFuture<TestResponse> stuck = CompletableFuture.supplyAsync(() -> post("k13", "u1", "slow"));
    assertThat(slowStarted.await(10, TimeUnit.SECONDS)).isTrue();
    // still within the in-flight timeout: another request under the key is refused
    assertThat(post("k13", "u1", "pick-9").status()).isEqualTo(422);
    now.set(now.get().plus(IN_FLIGHT_TIMEOUT).plusSeconds(1));
    TestResponse takeover = post("k13", "u1", "pick-9");
    assertThat(takeover.status()).isEqualTo(201);
    assertThat(takeover.header(Idempotency.REPLAYED_HEADER)).as("it ran: the first never finished").isNull();
    assertThat(runs.get()).isEqualTo(2);
    // the stuck request finishes now: its caller gets its answer, but the key is no longer its own
    slowGate.countDown();
    assertThat(stuck.get(10, TimeUnit.SECONDS).status()).isEqualTo(201);
    TestResponse replay = post("k13", "u1", "pick-9");
    assertThat(replay.header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(replay.body()).isEqualTo(takeover.body());
    assertThat(post("k13", "u1", "slow").status()).isEqualTo(422);
    assertThat(runs.get()).isEqualTo(2);
  }

  @Test
  void anExpiredKeyIsTakenOver() {
    post("k14", "u1", "pick-7");
    now.set(now.get().plus(RETENTION).minusSeconds(1));
    assertThat(post("k14", "u1", "pick-7").header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    now.set(now.get().plusSeconds(2));
    TestResponse ranAgain = post("k14", "u1", "pick-7");
    assertThat(ranAgain.status()).isEqualTo(201);
    assertThat(ranAgain.header(Idempotency.REPLAYED_HEADER)).isNull();
    // and under another request: an expired key is free
    now.set(now.get().plus(RETENTION).plusSeconds(1));
    assertThat(post("k14", "u1", "pick-8").status()).isEqualTo(201);
    assertThat(runs.get()).isEqualTo(3);
  }

  @Test
  void aFailureFreesTheKeySoARetryDoesTheWork() {
    assertThat(post("k5", "u1", "fail").status()).isEqualTo(409);
    assertThat(post("k5", "u1", "fail").status()).isEqualTo(409);
    assertThat(post("k6", "u1", "boom").status()).isEqualTo(500);
    assertThat(post("k6", "u1", "boom").status()).isEqualTo(500);
    assertThat(post("k7", "u1", "refuse").status()).isEqualTo(422);
    assertThat(post("k7", "u1", "refuse").status()).isEqualTo(422);
    assertThat(runs.get()).isEqualTo(6);
  }

  @Test
  void aStreamedAnswerIsNotKept() {
    assertThat(post("k8", "u1", "stream").body()).isEqualTo("s");
    assertThat(post("k8", "u1", "stream").body()).isEqualTo("s");
    assertThat(runs.get()).isEqualTo(2);
  }

  @Test
  void anAnswerWithoutABodyOrContentTypeIsReplayedAsItWas() {
    post("k9", "u1", "accept");
    TestResponse again = post("k9", "u1", "accept");
    assertThat(runs.get()).isEqualTo(1);
    assertThat(again.status()).isEqualTo(202);
    assertThat(again.header("Content-Type")).isNull();
    assertThat(again.body()).isEmpty();
    assertThat(again.header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
  }

  @Test
  void aKeyOnARequestWithoutAPrincipalIsRefused() {
    TestResponse r = post("k10", null, "a");
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body()).contains("IDEMPOTENCY_KEY_UNSUPPORTED");
    assertThat(runs.get()).isZero();
    // without the key the same request runs
    assertThat(post(null, null, "a").status()).isEqualTo(201);
  }
}
