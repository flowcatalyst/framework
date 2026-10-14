package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.locks.LocksModule;
import io.flowcatalyst.framework.locks.database.DatabaseLockStores;
import io.flowcatalyst.framework.locks.database.LocksDatabaseModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The lock-gated scheduled job end to end: PostgreSQL lock driver, the receiver, and completion
 * reports to the fake platform. Two applications on one database stand in for two replicas.
 */
class ScheduledJobLockIntegrationTest {

  static final String CODE = "nightly-export";
  static final String STORED_KEY = "svc:scheduled-job:" + CODE;

  static PostgresFixture pg;

  @TempDir Path dir;

  FakePlatform platform;
  final List<App> apps = new ArrayList<>();

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void setUp() throws IOException {
    pg.reset();
    platform = FakePlatform.start();
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
  }

  @AfterEach
  void tearDown() {
    apps.forEach(App::close);
    platform.close();
  }

  private TestClient replica(ScheduledJobHandler handler, String... env) {
    return replica(handler, UnaryOperator.identity(), env);
  }

  private TestClient replica(ScheduledJobHandler handler, UnaryOperator<ReceiverOptions> adjust, String... env) {
    App app =
        Receiving.start(
            dir,
            Receiving.env(platform, env),
            () -> NOW,
            MonotonicClock.system(),
            Receiving.receivers(adjust),
            new DatabaseModule(),
            LocksModule.builder().store("main", DatabaseLockStores.database()).build(),
            new LocksDatabaseModule(),
            Receiving.handlers(r -> r.onScheduledJob(CODE, handler)));
    apps.add(app);
    return Receiving.client(app);
  }

  private static String envelope(String instanceId) {
    return "{\"jobId\":\"sj_1\",\"jobCode\":\"" + CODE + "\",\"instanceId\":\"" + instanceId + "\","
        + "\"firedAt\":\"2026-10-09T12:00:00Z\",\"triggerKind\":\"CRON\",\"tracksCompletion\":true,"
        + "\"concurrent\":false}";
  }

  private static TestResponse fire(TestClient client, String instanceId) {
    String body = envelope(instanceId);
    return client
        .post("/fc/scheduled-jobs")
        .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
        .header("X-FlowCatalyst-Timestamp", TS)
        .body(body)
        .send();
  }

  private JsonNode completionOf(String instanceId) throws InterruptedException {
    long deadline = System.nanoTime() + 15_000_000_000L;
    while (System.nanoTime() < deadline) {
      for (FakePlatform.Recorded r : platform.instanceCalls("complete")) {
        if (r.path().equals("/api/scheduled-jobs/instances/" + instanceId + "/complete")) {
          return r.json();
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("no completion for " + instanceId);
  }

  @Test
  void aRunTakesTheDatabaseLockReportsSuccessAndReleasesIt() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    TestClient client =
        replica(
            (ctx, f) -> {
              inside.countDown();
              release.await(10, TimeUnit.SECONDS);
              return JobResult.of(java.util.Map.of("fence", f.fencingToken().orElseThrow()));
            });
    assertThat(fire(client, "i1").status()).isEqualTo(202);
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(pg.query("SELECT owner_token IS NOT NULL AND expires_at > clock_timestamp() FROM fw_lock WHERE lock_key = '" + STORED_KEY + "'"))
        .isEqualTo("t");
    release.countDown();
    JsonNode c = completionOf("i1");
    assertThat(c.get("status").asString()).isEqualTo("SUCCESS");
    assertThat(c.get("result").get("fence").asLong())
        .isEqualTo(Long.parseLong(pg.query("SELECT fencing_token FROM fw_lock WHERE lock_key = '" + STORED_KEY + "'")));
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!"t".equals(pg.query("SELECT owner_token IS NULL FROM fw_lock WHERE lock_key = '" + STORED_KEY + "'"))
        && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(pg.query("SELECT owner_token IS NULL FROM fw_lock WHERE lock_key = '" + STORED_KEY + "'")).isEqualTo("t");
  }

  @Test
  void twoReplicasFiredTogetherRunTheJobOnce() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    ScheduledJobHandler handler =
        (ctx, f) -> {
          runs.incrementAndGet();
          release.await(10, TimeUnit.SECONDS);
          return JobResult.success();
        };
    TestClient a = replica(handler);
    TestClient b = replica(handler);
    Thread ta = Thread.ofVirtual().start(() -> fire(a, "ia"));
    Thread tb = Thread.ofVirtual().start(() -> fire(b, "ib"));
    ta.join();
    tb.join();
    // One of them is skipped while the other runs.
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (platform.instanceCalls("complete").isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    JsonNode skipped = platform.instanceCalls("complete").getFirst().json();
    assertThat(skipped.get("result").get("reason").asString()).isEqualTo("lock-held");
    release.countDown();
    platform.awaitInstanceCalls("complete", 2, 10_000);
    assertThat(platform.instanceCalls("complete").get(1).json().get("status").asString()).isEqualTo("SUCCESS");
    assertThat(runs).hasValue(1);
  }

  @Test
  void aLockHeldByAnotherProcessSkipsTheRun() throws Exception {
    AtomicInteger runs = new AtomicInteger();
    TestClient client =
        replica(
            (ctx, f) -> {
              runs.incrementAndGet();
              return JobResult.success();
            });
    pg.execute(
        "INSERT INTO fw_lock (lock_key, owner_token, fencing_token, expires_at) VALUES ('"
            + STORED_KEY
            + "', 'other-replica', nextval('fw_lock_fence'), clock_timestamp() + interval '1 hour')");
    fire(client, "i1");
    JsonNode c = completionOf("i1");
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").get("skipped").asBoolean()).isTrue();
    assertThat(runs).hasValue(0);
  }

  @Test
  void aLeaseLostInTheDatabaseStopsTheHandlerAndReportsFailure() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    TestClient client =
        replica(
            (ctx, f) -> {
              inside.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
              }
              return JobResult.success();
            },
            o -> o.withJobLockLease(Duration.ofSeconds(3)));
    fire(client, "i1");
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
    // Another process took the key after an expiry: the refresh (every second) finds it lost.
    pg.execute(
        "UPDATE fw_lock SET owner_token = 'thief', fencing_token = nextval('fw_lock_fence'),"
            + " expires_at = clock_timestamp() + interval '1 hour' WHERE lock_key = '" + STORED_KEY + "'");
    assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
    JsonNode c = completionOf("i1");
    assertThat(c.get("status").asString()).isEqualTo("FAILURE");
    assertThat(c.get("result").get("error").asString()).isEqualTo("the overlap lock was lost while the job ran");
    // The thief's lock is untouched: a lost lease is never released over someone else's.
    assertThat(pg.query("SELECT owner_token FROM fw_lock WHERE lock_key = '" + STORED_KEY + "'")).isEqualTo("thief");
  }
}
