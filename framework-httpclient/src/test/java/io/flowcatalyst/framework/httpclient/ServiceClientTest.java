package io.flowcatalyst.framework.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.httpclient.FakeServer.Answer;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

/** The library against a real local server (http-client contract, sections 2 and 3). */
class ServiceClientTest {

  record Order(String id, int lines) {}

  FakeServer server;
  HttpClients clients;
  final List<String> attempts = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void start() throws IOException {
    server = FakeServer.start();
    clients = HttpClients.create();
  }

  @AfterEach
  void stop() {
    clients.close();
    server.close();
  }

  ServiceOptions.Builder options() {
    return ServiceOptions.builder("orders", server.base().resolve("/v1"))
        .retry(RetryPolicy.defaults().withBackoff(Duration.ofMillis(1), Duration.ofMillis(5)))
        .observer(
            (service, method, path, status, failure, nanos, attempt) ->
                attempts.add(method + " " + path + " " + (failure == null ? status : failure) + " #" + attempt));
  }

  ServiceClient client() {
    return clients.service(options().build());
  }

  // ---- requests ----------------------------------------------------------------------------

  @Test
  void getsJsonWithEncodedPathAndQueryUnderTheBasePath() {
    server.on("/v1/orders/a%2Fb%20c%3F%23", Answer.of(200, "{\"id\":\"x\",\"lines\":2,\"extra\":true}"));
    Order order =
        client()
            .get("/orders/{id}")
            .pathParam("id", "a/b c?#")
            .query("q", "x&y=z")
            .query("q", "é")
            .sendJson(Order.class);
    assertThat(order).isEqualTo(new Order("x", 2));
    FakeServer.Recorded r = server.requests().get(0);
    assertThat(r.rawQuery()).isEqualTo("q=x%26y%3Dz&q=%C3%A9");
    assertThat(r.header("User-Agent")).isEqualTo("flowcatalyst-framework-httpclient");
    assertThat(attempts).containsExactly("GET /orders/{id} 200 #1");
  }

  @Test
  void postsJsonAndDecodesGenericTypes() {
    server.on("/v1/orders", Answer.of(201, "[{\"id\":\"o1\",\"lines\":1}]"));
    List<Order> created =
        client().post("/orders").json(new Order("o1", 1)).sendJson(new TypeReference<List<Order>>() {});
    assertThat(created).containsExactly(new Order("o1", 1));
    FakeServer.Recorded r = server.requests().get(0);
    assertThat(r.method()).isEqualTo("POST");
    assertThat(r.body()).isEqualTo("{\"id\":\"o1\",\"lines\":1}");
    assertThat(r.header("Content-Type")).isEqualTo("application/json");
  }

  @Test
  void getJsonAndPostJsonShorthands() {
    server.on("/v1/orders/o%201", Answer.of(200, "{\"id\":\"o 1\",\"lines\":3}"));
    server.on("/v1/orders", Answer.of(200, "{\"id\":\"o2\",\"lines\":0}"));
    assertThat(client().getJson("/orders/{id}", Order.class, "o 1")).isEqualTo(new Order("o 1", 3));
    assertThat(client().postJson("/orders", Map.of("x", 1), Order.class)).isEqualTo(new Order("o2", 0));
    assertThatIllegalArgumentException().isThrownBy(() -> client().getJson("/orders/{id}", Order.class));
  }

  @Test
  void headFormAndRawBodies() {
    server.on("/v1/ping", Answer.of(200, "{}"));
    server.on("/v1/form", Answer.of(204, ""));
    assertThat(client().head("/ping").send().status()).isEqualTo(200);
    Map<String, String> fields = new java.util.LinkedHashMap<>();
    fields.put("a", "1 2");
    fields.put("b", "&");
    assertThat(client().post("/form").form(fields).send().status()).isEqualTo(204);
    assertThat(server.requests().get(1).body()).isEqualTo("a=1+2&b=%26");
    assertThat(server.requests().get(1).header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
  }

  @Test
  void pathsThatCouldLeaveTheServiceAreRefusedBeforeAnythingIsSent() {
    ServiceClient c = client();
    for (String bad :
        new String[] {
          "orders", "http://evil.example.com/x", "//evil.example.com/x", "/a//b", "/a?b=1", "/a#f",
          "/../admin", "/a/../b", "/a/./b", "/a\\b", "/a b", "/a/%zz", "/{x}/{x}"
        }) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> c.get(bad));
    }
    for (String bad : new String[] {"", ".", ".."}) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> c.get("/a/{id}").pathParam("id", bad));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> c.get("/a/{id}").pathParam("other", "x"));
    assertThatIllegalArgumentException().isThrownBy(() -> c.get("/a/{id}").send());
    assertThat(server.requests()).isEmpty();
  }

  @Test
  void headerInjectionAndFramingHeadersAreRefused() {
    ServiceClient c = client();
    assertThatIllegalArgumentException().isThrownBy(() -> c.get("/a").header("X-A", "v\r\nX-Evil: 1"));
    assertThatIllegalArgumentException().isThrownBy(() -> c.get("/a").header("X-A", "v\nx"));
    assertThatIllegalArgumentException().isThrownBy(() -> c.get("/a").header("Bad Name", "v"));
    for (String reserved : new String[] {"Host", "content-length", "Transfer-Encoding", "Connection"}) {
      assertThatIllegalArgumentException().isThrownBy(() -> c.get("/a").header(reserved, "x"));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> options().header("X", "a\r\nb").build());
    assertThatIllegalArgumentException().isThrownBy(() -> c.withHeaders(Map.of("X", "a\rb")));
    assertThatIllegalArgumentException().isThrownBy(() -> c.post("/a").idempotencyKey("k\r\n"));
  }

  @Test
  void baseUrlsAreChecked() {
    for (String bad :
        new String[] {"/relative", "ftp://h/x", "https://user:pw@h.example/x", "https://h.example/x?q=1", "https://h.example/#f"}) {
      assertThatIllegalArgumentException()
          .as(bad)
          .isThrownBy(() -> ServiceOptions.builder("x", URI.create(bad)).build());
    }
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ServiceOptions.builder("Bad", URI.create("https://h.example")).build());
  }

  // ---- statuses and failures -----------------------------------------------------------------

  @Test
  void aNonSuccessStatusIsAResponseAndSendJsonThrowsWithTheEnvelopeCodeButNoBody() {
    server.on("/v1/orders/1", Answer.of(409, "{\"error\":\"TAKEN\",\"message\":\"internal secret detail\"}"));
    ClientResponse r = client().get("/orders/{id}").pathParam("id", "1").send();
    assertThat(r.status()).isEqualTo(409);
    assertThat(r.isSuccess()).isFalse();
    Throwable t = catchThrowable(() -> client().get("/orders/{id}").pathParam("id", "1").sendJson(Order.class));
    assertThat(t).isInstanceOf(UnexpectedStatusException.class);
    UnexpectedStatusException e = (UnexpectedStatusException) t;
    assertThat(e.status()).isEqualTo(409);
    assertThat(e.errorCode()).isEqualTo("TAKEN");
    assertThat(e.getMessage()).contains("orders GET /orders/{id}").doesNotContain("secret");
  }

  @Test
  void aBodyThatIsNotTheJsonAskedForIsADecodeFailureWithoutTheBody() {
    server.on("/v1/x", Answer.of(200, "password=hunter2 not json"));
    Throwable t = catchThrowable(() -> client().get("/x").sendJson(Order.class));
    assertThat(t).isInstanceOf(HttpClientException.class);
    assertThat(((HttpClientException) t).kind()).isEqualTo(HttpClientException.Kind.DECODE);
    assertThat(t.getMessage()).doesNotContain("hunter2");
    assertThat(t.getCause()).isNull();
  }

  @Test
  void oversizedResponsesAreRefusedByLengthAndWhileReading() {
    byte[] big = "x".repeat(5000).getBytes(StandardCharsets.UTF_8);
    server.on("/v1/declared", new Answer(200, Map.of(), big, 0, false));
    server.on("/v1/chunked", new Answer(200, Map.of(), big, 0, true));
    ServiceClient c = clients.service(options().maxResponseBytes(1000).build());
    for (String path : List.of("/declared", "/chunked")) {
      Throwable t = catchThrowable(() -> c.get(path).send());
      assertThat(t).as(path).isInstanceOf(HttpClientException.class);
      assertThat(((HttpClientException) t).kind()).isEqualTo(HttpClientException.Kind.RESPONSE_TOO_LARGE);
    }
    // not retried: a larger answer will not get smaller
    assertThat(server.count("/v1/declared")).isEqualTo(1);
  }

  @Test
  void aSlowAnswerTimesOutAndIdempotentRequestsAreRetried() {
    server.on("/v1/slow", Answer.of(200, "{}").delayed(1500));
    ServiceClient c = clients.service(options().requestTimeout(Duration.ofMillis(150)).build());
    long start = System.nanoTime();
    Throwable t = catchThrowable(() -> c.get("/slow").send());
    assertThat(((HttpClientException) t).kind()).isEqualTo(HttpClientException.Kind.TIMEOUT);
    assertThat(System.nanoTime() - start).isLessThan(Duration.ofMillis(1400).toNanos());
    assertThat(attempts).hasSize(3).allMatch(a -> a.contains("TIMEOUT"));
  }

  @Test
  void aConnectionFailureIsConnectAndIsRetriedForGet() throws IOException {
    int closed;
    try (ServerSocket s = new ServerSocket(0)) {
      closed = s.getLocalPort();
    }
    ServiceClient c =
        clients.service(
            ServiceOptions.builder("down", URI.create("http://127.0.0.1:" + closed))
                .retry(RetryPolicy.defaults().withBackoff(Duration.ofMillis(1), Duration.ofMillis(2)))
                .observer((s, m, p, st, f, n, a) -> attempts.add(String.valueOf(f)))
                .build());
    Throwable t = catchThrowable(() -> c.get("/x").send());
    assertThat(((HttpClientException) t).kind()).isEqualTo(HttpClientException.Kind.CONNECT);
    assertThat(t.getMessage()).isEqualTo("down GET /x: could not connect");
    assertThat(attempts).containsExactly("CONNECT", "CONNECT", "CONNECT");
    attempts.clear();
    assertThat(catchThrowable(() -> c.post("/x").send())).isInstanceOf(HttpClientException.class);
    assertThat(attempts).containsExactly("CONNECT");
  }

  @Test
  void redirectsAreReturnedNotFollowed() {
    server.on("/v1/moved", Answer.of(302, "").with("Location", server.base() + "/elsewhere"));
    ClientResponse r = client().get("/moved").send();
    assertThat(r.status()).isEqualTo(302);
    assertThat(server.count("/elsewhere")).isZero();
  }

  // ---- retries ------------------------------------------------------------------------------

  @Test
  void idempotentRequestsAreRetriedOnRetryableStatuses() {
    server.on("/v1/flaky", (r, n) -> n < 3 ? Answer.of(503, "{}") : Answer.of(200, "{\"id\":\"ok\",\"lines\":0}"));
    assertThat(client().get("/flaky").sendJson(Order.class).id()).isEqualTo("ok");
    assertThat(server.count("/v1/flaky")).isEqualTo(3);
  }

  @Test
  void postAndPatchAreNotRetriedUnlessMadeSafe() {
    server.on("/v1/p", Answer.of(503, "{}"));
    assertThat(client().post("/p").json(Map.of()).send().status()).isEqualTo(503);
    assertThat(client().patch("/p").json(Map.of()).send().status()).isEqualTo(503);
    assertThat(server.count("/v1/p")).isEqualTo(2);

    assertThat(client().post("/p").idempotencyKey("k-1").send().status()).isEqualTo(503);
    assertThat(server.count("/v1/p")).isEqualTo(5);
    assertThat(server.requests().get(4).header("Idempotency-Key")).isEqualTo("k-1");

    assertThat(client().post("/p").retryable(true).send().status()).isEqualTo(503);
    assertThat(server.count("/v1/p")).isEqualTo(8);

    assertThat(client().get("/p").retryable(false).send().status()).isEqualTo(503);
    assertThat(server.count("/v1/p")).isEqualTo(9);
  }

  @Test
  void otherStatusesAreNotRetried() {
    server.on("/v1/bad", Answer.of(500, "{}"));
    server.on("/v1/nf", Answer.of(404, "{}"));
    assertThat(client().get("/bad").send().status()).isEqualTo(500);
    assertThat(client().get("/nf").send().status()).isEqualTo(404);
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void retryAfterIsHonouredAndATooLongOneReturnsTheResponseAtOnce() {
    server.on("/v1/limited", (r, n) -> n == 1 ? Answer.of(429, "{}").with("Retry-After", "1") : Answer.of(200, "{}"));
    long start = System.nanoTime();
    assertThat(client().get("/limited").send().status()).isEqualTo(200);
    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(950).toNanos());

    server.on("/v1/later", Answer.of(503, "{}").with("Retry-After", "120"));
    start = System.nanoTime();
    assertThat(client().get("/later").send().status()).isEqualTo(503);
    assertThat(System.nanoTime() - start).isLessThan(Duration.ofMillis(900).toNanos());
    assertThat(server.count("/v1/later")).isEqualTo(1);
  }

  @Test
  void whenAttemptsRunOutTheLastResponseIsReturned() {
    server.on("/v1/down", Answer.of(502, "{}"));
    ServiceClient c = clients.service(options().retry(RetryPolicy.defaults().withMaxAttempts(4).withBackoff(Duration.ZERO, Duration.ZERO)).build());
    assertThat(c.get("/down").send().status()).isEqualTo(502);
    assertThat(server.count("/v1/down")).isEqualTo(4);
    ServiceClient none = clients.service(options().retry(RetryPolicy.none()).build());
    assertThat(none.get("/down").send().status()).isEqualTo(502);
    assertThat(server.count("/v1/down")).isEqualTo(5);
  }

  @Test
  void anInterruptedCallerGetsInterruptedWithItsFlagSet() throws Exception {
    server.on("/v1/slow", Answer.of(200, "{}").delayed(3000));
    AtomicInteger outcome = new AtomicInteger();
    Thread t =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    client().get("/slow").send();
                  } catch (HttpClientException e) {
                    if (e.kind() == HttpClientException.Kind.INTERRUPTED && Thread.currentThread().isInterrupted()) {
                      outcome.set(1);
                    }
                  }
                });
    Thread.sleep(200);
    t.interrupt();
    t.join(5000);
    assertThat(outcome).hasValue(1);
  }

  @Test
  void withHeadersAddsHeadersToEveryRequest() {
    server.on("/v1/x", Answer.of(200, "{}"));
    client().withHeaders(Map.of("X-Correlation-Id", "c-1")).get("/x").send();
    assertThat(server.requests().get(0).header("X-Correlation-Id")).isEqualTo("c-1");
  }
}
