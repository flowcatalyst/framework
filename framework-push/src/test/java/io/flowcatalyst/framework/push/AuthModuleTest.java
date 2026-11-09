package io.flowcatalyst.framework.push;

import static io.flowcatalyst.framework.push.TestNode.ACME;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.auth.AuthModule;
import io.flowcatalyst.framework.auth.AuthTesting;
import io.flowcatalyst.framework.auth.FieldOptions;
import io.flowcatalyst.framework.auth.FieldPrincipal;
import io.flowcatalyst.framework.auth.MemorySessionStore;
import io.flowcatalyst.framework.auth.PermissionMapper;
import io.flowcatalyst.framework.auth.TestFieldDevice;
import io.flowcatalyst.framework.auth.TestOidcProvider;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Subscribes authenticated by the real auth module: bearer, field token with DPoP, session with its CSRF rule. */
class AuthModuleTest {

  static final String ORIGIN = "https://app.example";

  static PostgresFixture pg;

  @TempDir Path dir;
  TestClock clock;
  TestOidcProvider provider;
  TestNode n;
  TestFieldDevice device;
  AuthTesting auth;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  @BeforeEach
  void start() throws Exception {
    pg.reset();
    clock = new TestClock(TestNode.BEGIN);
    provider = TestOidcProvider.start(clock);
    provider.client("app-client", "", ORIGIN + "/auth/callback");
    AuthModule authModule =
        AuthModule.builder()
            .application(new ApplicationCode("test"))
            .fieldWorkers(FieldOptions.builder().build())
            .sessions(lookup -> MemorySessionStore.create(lookup.get(InstantSource.class)))
            .permissions(PermissionMapper.byRole(Map.of("test:viewer", Set.of("test:order:view"))))
            .build();
    n =
        TestNode.start(
            pg,
            dir,
            TestNode.Config.defaults()
                .withClock(clock)
                .withEnv(
                    "AUTH_ISSUER", provider.issuer().toString(),
                    "AUTH_CLIENT_ID", "app-client",
                    "AUTH_PUBLIC_BASE_URL", ORIGIN,
                    "AUTH_COOKIE_KEYS", "k1:" + Base64.getUrlEncoder().withoutPadding().encodeToString("k".repeat(32).getBytes(StandardCharsets.UTF_8)))
                .withExtra(authModule)
                .withPush(
                    b -> {
                      b.authenticate(PushAuth.authModule());
                      b.topic("test:field-worker:{principal}:jobs");
                    }));
    device = TestFieldDevice.create(clock);
    auth = AuthTesting.of(n.app);
  }

  @AfterEach
  void close() {
    n.close();
    provider.close();
  }

  static final FieldPrincipal WORKER = AuthTesting.fieldWorker("fwu_W1", "acme", FieldPrincipal.DeviceMode.PERSONAL);

  String[] field(TestFieldDevice d) {
    AuthTesting.FieldRequest r = auth.fieldRequest(WORKER, d, provider::signField, "POST", "/push/subscriptions");
    return new String[] {"Authorization", r.authorization(), "DPoP", r.dpop()};
  }

  @Test
  void aFieldWorkerSubscribesWithItsTokenAndDPoP() throws Exception {
    TestNode.Answer a =
        n.call(
            "POST",
            "/push/subscriptions",
            TestNode.subscribeBody(List.of("test:field-worker:fwu_W1:jobs", "test:field-worker:fwu_W2:jobs"), List.of()),
            field(device));
    assertThat(a.status()).as(a.body()).isEqualTo(200);
    JsonNode m = a.json();
    assertThat(HttpTest.granted(m)).isEqualTo("test:field-worker:fwu_W1:jobs");
    assertThat(HttpTest.denials(m)).isEqualTo("test:field-worker:fwu_W2:jobs=TOPIC_FORBIDDEN");
    assertThat(m.path("expiresAt").asString()).as("the field token's expiry").isEqualTo(TestNode.BEGIN.plus(Duration.ofMinutes(10)).toString());
    String token = m.path("streamToken").asString();
    StreamTokens.Valid claims = (StreamTokens.Valid) n.pushes.tokens().verify(token, clock.instant());
    Subscription sub = claims.claims().subscription();
    assertThat(sub.client()).isEqualTo("acme");
    assertThat(sub.principal()).isEqualTo("fwu_W1");
    assertThat(sub.device()).isEqualTo("fwd_TEST");
    assertThat(sub.session()).isEqualTo("fws_TEST");
    Sse c = n.listen(token, null);
    c.greeting();
    n.publish(ACME, "test:field-worker:fwu_W1:jobs", "job.assigned", Map.of("jobId", "job_1"));
    assertThat(Sse.idOf(c.next())).isEqualTo(1);
    // the platform revokes the login family: the stream ends with auth-expired
    n.pushes.endStreams(Holder.of("", "", "fws_TEST"));
    assertThat(c.next()).isEqualTo("event: auth-expired\ndata: {}\n\n");
    assertThat(c.ends(Duration.ofSeconds(10))).isTrue();
  }

  @Test
  void aTicketBoundToTheDeviceKey() throws Exception {
    String ticket = n.pushes.ticketWith(Context.of(ACME), new Pushes.TicketOptions(device.jkt(), null), "test:order:{id}");
    String body = TestNode.subscribeBody(List.of("test:order:1"), List.of(ticket));
    TestNode.Answer ok = n.call("POST", "/push/subscriptions", body, field(device));
    assertThat(ok.status()).as("the device that holds the key: %s", ok.body()).isEqualTo(200);
    assertThat(HttpTest.granted(ok.json())).isEqualTo("test:order:1");
    TestNode.Answer another = n.call("POST", "/push/subscriptions", body, field(TestFieldDevice.create(clock)));
    assertThat(another.status()).as("another device: %s", another.body()).isEqualTo(403);
    assertThat(another.code()).isEqualTo("NO_TOPICS");
    assertThat(n.call("POST", "/push/subscriptions", body).status()).as("no credentials, no proof").isEqualTo(401);
  }

  @Test
  void aSessionAndABearerTokenThroughTheAuthModule() throws Exception {
    String cookie = auth.cookie(AuthTesting.clientUser("prn_1", "clt_1", "acme", "test:viewer"));
    String body = TestNode.subscribeBody(List.of("test:order:1"), List.of());
    TestNode.Answer session = n.call("POST", "/push/subscriptions", body, "Cookie", cookie, "Sec-Fetch-Site", "same-origin");
    assertThat(session.status()).as(session.body()).isEqualTo(200);
    assertThat(HttpTest.granted(session.json())).isEqualTo("test:order:1");
    TestNode.Answer cross = n.call("POST", "/push/subscriptions", body, "Cookie", cookie, "Sec-Fetch-Site", "cross-site");
    assertThat(cross.status()).as("a cross-site subscribe with the session: %s", cross.body()).isEqualTo(403);
    assertThat(n.call("POST", "/push/subscriptions", body, "Authorization", "Bearer not-a-token").status()).as("a bad bearer token").isEqualTo(401);
    String ticket = n.pushes.ticket(Context.of(ACME), "test:order:{id}");
    String withTicket = TestNode.subscribeBody(List.of("test:order:1"), List.of(ticket));
    assertThat(n.call("POST", "/push/subscriptions", withTicket, "Authorization", "Bearer not-a-token").status())
        .as("a bad bearer token with a good ticket")
        .isEqualTo(401);
    assertThat(n.call("POST", "/push/subscriptions", withTicket).status()).as("a ticket alone").isEqualTo(200);
  }
}
