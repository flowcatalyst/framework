package io.flowcatalyst.framework.httpclient;

import static org.assertj.core.api.Assertions.assertThat;

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

/** The capture scope: what a bound sink sees of each attempt, and that it never affects the call. */
class HttpCaptureTest {

  FakeServer server;
  HttpClients clients;
  final List<HttpCapture.Exchange> seen = Collections.synchronizedList(new ArrayList<>());

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

  ServiceClient client(ServiceOptions.Builder builder) {
    return clients.service(
        builder.retry(RetryPolicy.defaults().withBackoff(Duration.ofMillis(1), Duration.ofMillis(5))).build());
  }

  ServiceClient client() {
    return client(ServiceOptions.builder("orders", server.base().resolve("/v1")));
  }

  static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Test
  void unboundNeverCallsTheSinkAndTheCallBehavesAsBefore() {
    server.on("/v1/a", Answer.of(200, "{\"ok\":true}"));
    assertThat(HttpCapture.CURRENT.isBound()).isFalse();
    assertThat(client().get("/a").send().status()).isEqualTo(200);
    assertThat(seen).isEmpty();
  }

  @Test
  void boundGivesOneExchangePerAttemptWithEverythingSentAndReceived() {
    server.on("/v1/orders", Answer.of(201, "{\"id\":\"o1\"}").with("X-Trace", "t-1"));
    ServiceClient c =
        client(ServiceOptions.builder("orders", server.base().resolve("/v1")).header("X-Default", "d"));
    ScopedValue.where(HttpCapture.CURRENT, (HttpCapture.Sink) seen::add)
        .run(() -> c.post("/orders").query("q", "a b").query("page", "2").json(Map.of("n", 1)).send());
    assertThat(seen).hasSize(1);
    HttpCapture.Exchange e = seen.get(0);
    assertThat(e.service()).isEqualTo("orders");
    assertThat(e.method()).isEqualTo(RequestMethod.POST);
    assertThat(e.url().getRawPath()).isEqualTo("/v1/orders");
    assertThat(e.url().getRawQuery()).isEqualTo("q=a%20b&page=2");
    assertThat(text(e.requestBody())).isEqualTo("{\"n\":1}");
    assertThat(e.requestHeaders()).containsEntry("User-Agent", List.of("flowcatalyst-framework-httpclient"));
    assertThat(e.requestHeaders()).containsEntry("X-Default", List.of("d"));
    assertThat(e.requestHeaders()).containsEntry("Content-Type", List.of("application/json"));
    assertThat(e.status()).isEqualTo(201);
    assertThat(e.responseHeaders()).containsEntry("x-trace", List.of("t-1"));
    assertThat(text(e.responseBody())).isEqualTo("{\"id\":\"o1\"}");
    assertThat(e.failure()).isNull();
    assertThat(e.nanos()).isPositive();
    assertThat(e.attempt()).isEqualTo(1);
  }

  @Test
  void aRequestWithoutABodyHasANullRequestBody() {
    server.on("/v1/a", Answer.of(200, "{}"));
    ServiceClient c = client();
    ScopedValue.where(HttpCapture.CURRENT, (HttpCapture.Sink) seen::add).run(() -> c.get("/a").send());
    assertThat(seen.get(0).requestBody()).isNull();
  }

  @Test
  void aRetriedRequestIsTwoExchangesWithAttemptsOneAndTwo() {
    server.on("/v1/flaky", (r, n) -> n < 2 ? Answer.of(503, "{\"e\":1}") : Answer.of(200, "{\"ok\":1}"));
    ServiceClient c = client();
    ScopedValue.where(HttpCapture.CURRENT, (HttpCapture.Sink) seen::add).run(() -> c.get("/flaky").send());
    assertThat(seen).extracting(HttpCapture.Exchange::attempt).containsExactly(1, 2);
    assertThat(seen).extracting(HttpCapture.Exchange::status).containsExactly(503, 200);
    assertThat(text(seen.get(0).responseBody())).isEqualTo("{\"e\":1}");
  }

  @Test
  void aTransportFailureHasStatusZeroAKindAndNoResponse() throws IOException {
    int closed;
    try (ServerSocket s = new ServerSocket(0)) {
      closed = s.getLocalPort();
    }
    ServiceClient c = client(ServiceOptions.builder("down", URI.create("http://127.0.0.1:" + closed)));
    ScopedValue.where(HttpCapture.CURRENT, (HttpCapture.Sink) seen::add)
        .run(() -> {
          try {
            c.post("/x").json(Map.of("a", 1)).send();
          } catch (HttpClientException expected) {
            // the failure is the point
          }
        });
    assertThat(seen).hasSize(1);
    HttpCapture.Exchange e = seen.get(0);
    assertThat(e.status()).isZero();
    assertThat(e.failure()).isEqualTo(HttpClientException.Kind.CONNECT);
    assertThat(e.responseBody()).isNull();
    assertThat(e.responseHeaders()).isEmpty();
    assertThat(text(e.requestBody())).isEqualTo("{\"a\":1}");
    assertThat(e.url().getPort()).isEqualTo(closed);
  }

  @Test
  void sensitiveHeadersAreRedactedAndOthersKept() {
    server.on("/v1/a", Answer.of(200, "{}").with("Set-Cookie", "sid=abc").with("X-Request-Id", "r1"));
    ServiceClient c =
        client(
            ServiceOptions.builder("orders", server.base().resolve("/v1"))
                .auth(Auth.bearer(() -> "tok-123")));
    ScopedValue.where(HttpCapture.CURRENT, (HttpCapture.Sink) seen::add)
        .run(
            () ->
                c.post("/a")
                    .header("X-Api-Key", "k-1")
                    .header("Cookie", "sid=zzz")
                    .json(Map.of())
                    .send());
    HttpCapture.Exchange e = seen.get(0);
    assertThat(e.requestHeaders()).containsEntry("Authorization", List.of("[redacted]"));
    assertThat(e.requestHeaders()).containsEntry("X-Api-Key", List.of("[redacted]"));
    assertThat(e.requestHeaders()).containsEntry("Cookie", List.of("[redacted]"));
    assertThat(e.requestHeaders()).containsEntry("Content-Type", List.of("application/json"));
    assertThat(e.responseHeaders()).containsEntry("set-cookie", List.of("[redacted]"));
    assertThat(e.responseHeaders()).containsEntry("x-request-id", List.of("r1"));
    assertThat(e.requestHeaders().toString()).doesNotContain("tok-123").doesNotContain("k-1");
    // the request really carried the credential
    assertThat(server.requests().get(0).header("Authorization")).isEqualTo("Bearer tok-123");
  }

  @Test
  void headerMapsAreUnmodifiable() {
    server.on("/v1/a", Answer.of(200, "{}"));
    ServiceClient c = client();
    ScopedValue.where(HttpCapture.CURRENT, (HttpCapture.Sink) seen::add).run(() -> c.get("/a").send());
    HttpCapture.Exchange e = seen.get(0);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> e.requestHeaders().put("x", List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> e.responseHeaders().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aSinkThatThrowsDoesNotBreakTheCallOrItsRetries() {
    server.on("/v1/flaky", (r, n) -> n < 2 ? Answer.of(503, "{}") : Answer.of(200, "{\"ok\":1}"));
    ServiceClient c = client();
    AtomicInteger calls = new AtomicInteger();
    int status =
        ScopedValue.where(
                HttpCapture.CURRENT,
                (HttpCapture.Sink)
                    x -> {
                      calls.incrementAndGet();
                      throw new IllegalStateException("sink broke");
                    })
            .call(() -> c.get("/flaky").send().status());
    assertThat(status).isEqualTo(200);
    assertThat(calls).hasValue(2);
    assertThat(server.count("/v1/flaky")).isEqualTo(2);
  }

  @Test
  void redactHeadersCopiesAndKeepsValueCounts() {
    Map<String, List<String>> in = Map.of("X-Auth-Thing", List.of("a", "b"), "Accept", List.of("x"));
    Map<String, List<String>> out = HttpCapture.redactHeaders(in);
    assertThat(out).containsEntry("X-Auth-Thing", List.of("[redacted]", "[redacted]")).containsEntry("Accept", List.of("x"));
    assertThat(HttpCapture.redactHeaders(Map.of())).isEmpty();
    for (String name : List.of("Proxy-Authorization", "X-API-KEY", "x-amz-security-token", "Client-Secret", "Password", "X-Signature", "Set-Cookie", "X-Session-Id")) {
      assertThat(HttpCapture.isSensitiveHeader(name)).as(name).isTrue();
    }
    assertThat(HttpCapture.isSensitiveHeader("Content-Type")).isFalse();
  }
}
