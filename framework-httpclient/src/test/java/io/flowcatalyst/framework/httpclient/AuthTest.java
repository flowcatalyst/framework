package io.flowcatalyst.framework.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.httpclient.FakeServer.Answer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Auth strategies and the client-credentials token cache (http-client contract, section 4). */
class AuthTest {

  static final String SECRET = "cl1ent-s3cret";

  FakeServer server;
  HttpClients clients;
  final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  final AtomicInteger tokensIssued = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = FakeServer.start();
    clients = HttpClients.create(new HttpClients.Settings(2, 50, nanos::get, InstantSource.system()));
    server.on(
        "/oauth/token",
        (r, n) -> {
          int issued = tokensIssued.incrementAndGet();
          return Answer.of(200, "{\"access_token\":\"tok-" + issued + "\",\"token_type\":\"Bearer\",\"expires_in\":60}");
        });
    server.on(
        "/api/me",
        (r, n) -> Answer.of(200, "{\"auth\":\"" + r.header("Authorization") + "\"}"));
  }

  @AfterEach
  void stop() {
    clients.close();
    server.close();
  }

  ServiceClient client(Auth auth) {
    return clients.service(
        ServiceOptions.builder("api", server.base())
            .auth(auth)
            .retry(RetryPolicy.defaults().withBackoff(Duration.ZERO, Duration.ZERO))
            .build());
  }

  ClientCredentials.Builder credentials() {
    return ClientCredentials.builder(server.base().resolve("/oauth/token"), "orders-service", () -> SECRET);
  }

  String authOf(ServiceClient c) {
    return c.get("/api/me").send().bodyText();
  }

  @Test
  void bearerBasicAndHeaderStrategies() {
    AtomicInteger asked = new AtomicInteger();
    assertThat(authOf(client(Auth.bearer(() -> "t-" + asked.incrementAndGet())))).contains("Bearer t-1");
    String basic = Base64.getEncoder().encodeToString("ada:pw".getBytes(StandardCharsets.UTF_8));
    assertThat(authOf(client(Auth.basic("ada", () -> "pw")))).contains("Basic " + basic);
    client(Auth.header("X-Api-Key", () -> "k-1")).get("/api/me").send();
    assertThat(server.requests().get(2).header("X-Api-Key")).isEqualTo("k-1");
    assertThat(authOf(client(Auth.none()))).contains("\"auth\":\"null\"");
    assertThatIllegalArgumentException().isThrownBy(() -> Auth.basic("a:b", () -> "x"));
    assertThat(Auth.bearer(() -> SECRET).toString()).doesNotContain(SECRET);
  }

  @Test
  void aBearerWithACallbackIsRefreshedOnceAfterA401() {
    server.on("/api/strict", (r, n) -> "Bearer good".equals(r.header("Authorization")) ? Answer.of(200, "{}") : Answer.of(401, "{}"));
    List<String> tokens = new ArrayList<>(List.of("stale", "good"));
    AtomicInteger cleared = new AtomicInteger();
    ServiceClient c = client(Auth.bearer(() -> tokens.get(0), () -> { cleared.incrementAndGet(); tokens.remove(0); }));
    assertThat(c.post("/api/strict").send().status()).isEqualTo(200); // even a POST: a 401 was not processed
    assertThat(cleared).hasValue(1);
    assertThat(server.count("/api/strict")).isEqualTo(2);

    // A second 401 is returned, not looped on.
    ServiceClient never = client(Auth.bearer(() -> "bad", cleared::incrementAndGet));
    assertThat(never.get("/api/strict").send().status()).isEqualTo(401);
    assertThat(server.count("/api/strict")).isEqualTo(4);

    // Without a callback a 401 is simply returned.
    assertThat(client(Auth.bearer(() -> "bad")).get("/api/strict").send().status()).isEqualTo(401);
  }

  @Test
  void clientCredentialsFetchOneTokenAndReuseItUntilNearExpiry() {
    ServiceClient c = client(Auth.clientCredentials(credentials().scope("billing").build()));
    assertThat(authOf(c)).contains("Bearer tok-1");
    assertThat(authOf(c)).contains("Bearer tok-1");
    assertThat(authOf(c)).contains("Bearer tok-1");
    assertThat(tokensIssued).hasValue(1);
    FakeServer.Recorded token = server.requests().get(0);
    assertThat(token.method()).isEqualTo("POST");
    assertThat(token.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
    assertThat(token.body())
        .isEqualTo("grant_type=client_credentials&scope=billing&client_id=orders-service&client_secret=" + SECRET);

    // expires_in 60 s, skew min(30 s, 6 s) = 6 s: still valid at 53 s, renewed at 54 s.
    nanos.addAndGet(Duration.ofSeconds(53).toNanos());
    assertThat(authOf(c)).contains("Bearer tok-1");
    nanos.addAndGet(Duration.ofSeconds(1).toNanos());
    assertThat(authOf(c)).contains("Bearer tok-2");
  }

  @Test
  void basicClientAuthenticationSendsTheSecretInAHeader() {
    ServiceClient c = client(Auth.clientCredentials(credentials().method(ClientCredentials.Method.BASIC).build()));
    authOf(c);
    FakeServer.Recorded token = server.requests().get(0);
    assertThat(token.body()).isEqualTo("grant_type=client_credentials");
    String expected = Base64.getEncoder().encodeToString(("orders-service:" + SECRET).getBytes(StandardCharsets.UTF_8));
    assertThat(token.header("Authorization")).isEqualTo("Basic " + expected);
  }

  @Test
  void a401DropsTheTokenAndTheRequestIsSentOnceMoreWithANewOne() {
    server.on("/api/fresh", (r, n) -> "Bearer tok-1".equals(r.header("Authorization")) ? Answer.of(401, "{}") : Answer.of(200, "{}"));
    ServiceClient c = client(Auth.clientCredentials(credentials().build()));
    assertThat(c.post("/api/fresh").send().status()).isEqualTo(200);
    assertThat(tokensIssued).hasValue(2);
    assertThat(server.count("/api/fresh")).isEqualTo(2);
  }

  @Test
  void concurrentCallersShareOneTokenRequest() throws Exception {
    server.on(
        "/oauth/token",
        (r, n) -> {
          tokensIssued.incrementAndGet();
          return Answer.of(200, "{\"access_token\":\"shared\",\"expires_in\":300}").delayed(200);
        });
    ServiceClient c = client(Auth.clientCredentials(credentials().build()));
    CountDownLatch go = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    AtomicInteger ok = new AtomicInteger();
    for (int i = 0; i < 20; i++) {
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      go.await();
                      if (authOf(c).contains("Bearer shared")) {
                        ok.incrementAndGet();
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
    }
    go.countDown();
    for (Thread t : threads) {
      t.join(10_000);
    }
    assertThat(ok).hasValue(20);
    assertThat(tokensIssued).hasValue(1);
  }

  @Test
  void aFailingTokenEndpointIsAnAuthFailureThatNeverShowsTheSecretOrTheAnswer() {
    server.on("/oauth/token", Answer.of(401, "{\"error\":\"invalid_client\",\"echo\":\"" + SECRET + "\"}"));
    Throwable t = catchThrowable(() -> authOf(client(Auth.clientCredentials(credentials().build()))));
    assertThat(t).isInstanceOf(HttpClientException.class);
    assertThat(((HttpClientException) t).kind()).isEqualTo(HttpClientException.Kind.AUTH);
    assertThat(t.getMessage()).contains("127.0.0.1").contains("401").doesNotContain(SECRET).doesNotContain("invalid_client");
    assertThat(server.count("/api/me")).isZero();
  }

  @Test
  void malformedTokenAnswersAreRefused() {
    for (String body :
        new String[] {
          "not json", "{}", "{\"access_token\":\"\"}", "{\"access_token\":\"t\",\"token_type\":\"mac\"}",
          "{\"access_token\":\"t\",\"expires_in\":-5}", "{\"access_token\":\"a\\r\\nX-Evil: 1\"}"
        }) {
      server.on("/oauth/token", Answer.of(200, body));
      Throwable t = catchThrowable(() -> authOf(client(Auth.clientCredentials(credentials().build()))));
      assertThat(t).as(body).isInstanceOf(HttpClientException.class);
      assertThat(((HttpClientException) t).kind()).as(body).isEqualTo(HttpClientException.Kind.AUTH);
    }
  }

  @Test
  void tokenUrlsMustBeHttpsOrLoopback() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientCredentials.builder(URI.create("http://login.example.com/token"), "c", () -> "s").build());
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientCredentials.builder(URI.create("https://u:p@login.example.com/token"), "c", () -> "s").build());
    assertThat(ClientCredentials.builder(URI.create("https://login.example.com/token"), "c", () -> SECRET).build().toString())
        .doesNotContain(SECRET);
    assertThat(ClientCredentials.builder(URI.create("http://localhost:8080/token"), "c", () -> "s").build()).isNotNull();
  }

  @Test
  void aSupplierThatThrowsIsAnAuthFailure() {
    Throwable t = catchThrowable(() -> authOf(client(Auth.bearer(() -> { throw new IllegalStateException("vault down: " + SECRET); }))));
    assertThat(((HttpClientException) t).kind()).isEqualTo(HttpClientException.Kind.AUTH);
    assertThat(t.getMessage()).doesNotContain(SECRET);
    assertThat(t.getCause()).isNull();
  }
}
