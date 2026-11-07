package io.flowcatalyst.framework.http.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdempotencyTest {

  private static final InstantSource CLOCK = InstantSource.fixed(Instant.parse("2026-11-07T10:00:00Z"));

  /** The caller is the X-User header (none: a context without a principal); the client is X-Client or acme. */
  private static final ContextResolver RESOLVER =
      request -> {
        String client = request.header("X-Client");
        Context c = Context.of(new ClientId(client == null ? "acme" : client));
        String user = request.header("X-User");
        return user == null ? c : c.withPrincipal(new PrincipalId(user));
      };

  private final MemoryIdempotencyStore store = new MemoryIdempotencyStore();
  private final AtomicInteger runs = new AtomicInteger();
  private final TestClient client;

  IdempotencyTest() {
    Routes routes = new Routes();
    RouteOptions once = RouteOptions.requiresContext().with(Idempotency.middleware(store, CLOCK));
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
          return Response.json(201, Map.of("run", n, "body", body)).header("Location", "/claims/" + n);
        },
        once);
    client = TestClient.of(routes, RESOLVER);
  }

  private TestResponse post(String key, String user, String body) {
    TestClient.Call call = client.post("/claims").header("X-User", user).body(body);
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
  void withoutTheHeaderOrWithABlankOneEveryRequestRuns() {
    post(null, "u1", "a");
    post(null, "u1", "a");
    post("  ", "u1", "a");
    assertThat(runs.get()).isEqualTo(3);
  }

  @Test
  void spacesAroundTheKeyAreNotPartOfIt() {
    post("k11", "u1", "pick-7");
    TestResponse again = post("  k11 ", "u1", "pick-7");
    assertThat(again.header(Idempotency.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(runs.get()).isEqualTo(1);
  }

  @Test
  void anotherRequestUnderTheSameKeyIsRefused() {
    post("k2", "u1", "pick-7");
    TestResponse other = post("k2", "u1", "pick-8");
    assertThat(other.status()).isEqualTo(422);
    assertThat(other.body()).contains("IDEMPOTENCY_KEY_REUSED");
    TestResponse otherPath = client.post("/nowhere").header("X-User", "u1").header(Idempotency.HEADER, "k2").body("pick-7").send();
    assertThat(otherPath.status()).isEqualTo(404);
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
  void aRepeatWhileTheFirstRunsIsAskedToRetry() {
    String hash =
        Idempotency.hash(
            io.flowcatalyst.framework.http.Request.of(
                new io.flowcatalyst.framework.http.RawRequest(
                    io.flowcatalyst.framework.http.Method.POST,
                    "/claims",
                    null,
                    Map.of(),
                    new java.io.ByteArrayInputStream("pick-7".getBytes(StandardCharsets.UTF_8)),
                    6,
                    1024,
                    "HTTP/1.1")));
    store.reserve(new IdempotencyScope("acme", "u1", "k4"), hash, CLOCK.instant());
    TestResponse busy = post("k4", "u1", "pick-7");
    assertThat(busy.status()).isEqualTo(409);
    assertThat(busy.body()).contains("IDEMPOTENCY_IN_PROGRESS");
    assertThat(runs.get()).isZero();
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
  void aContextWithoutAPrincipalRunsAsItIs() {
    client.post("/claims").header(Idempotency.HEADER, "k10").body("a").send();
    client.post("/claims").header(Idempotency.HEADER, "k10").body("a").send();
    assertThat(runs.get()).isEqualTo(2);
  }

  @Test
  void aKeyOverTwoHundredCharactersIsRefused() {
    TestResponse r = post("k".repeat(Idempotency.MAX_KEY_LENGTH + 1), "u1", "a");
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body()).contains("IDEMPOTENCY_KEY_INVALID");
    assertThat(post("k".repeat(Idempotency.MAX_KEY_LENGTH), "u1", "a").status()).isEqualTo(201);
    assertThat(runs.get()).isEqualTo(1);
  }
}
