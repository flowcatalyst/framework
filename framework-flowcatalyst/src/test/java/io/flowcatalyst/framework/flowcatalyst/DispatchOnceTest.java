package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.flowcatalyst.Receiving.NOW;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.SECRET;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.TS;
import static io.flowcatalyst.framework.flowcatalyst.Receiving.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link DispatchDelivery#once} against PostgreSQL, alone and through the receiver. */
class DispatchOnceTest {

  static final String TYPE = "orders:billing:invoice:created";

  static PostgresFixture pg;

  @TempDir Path dir;

  FakePlatform platform;
  App app;
  Database db;

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
    pg.execute("CREATE TABLE invoices_sent (job_id text NOT NULL)");
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
    if (app != null) {
      app.close();
    }
    platform.close();
  }

  private void start(Consumer<Receivers> registrar) {
    app =
        Receiving.start(
            dir,
            Receiving.env(platform),
            () -> NOW,
            new DatabaseModule(),
            Receiving.handlers(registrar));
    db = app.get(Databases.class).defaultInstance();
  }

  private static DispatchDelivery delivery(String jobId) {
    return new DispatchDelivery(
        jobId, TYPE, 1, null, null, null, CorrelationId.generate(), null, false, null, new byte[0]);
  }

  private static Integer send(java.sql.Connection tx, String jobId) throws Exception {
    try (PreparedStatement s = tx.prepareStatement("INSERT INTO invoices_sent (job_id) VALUES (?)")) {
      s.setString(1, jobId);
      return s.executeUpdate();
    }
  }

  private static int sent() {
    return Integer.parseInt(pg.query("SELECT count(*) FROM invoices_sent"));
  }

  @Test
  void theModuleContributesTheTable() {
    start(r -> {});
    assertThat(pg.query("SELECT to_regclass('fw_dispatch_once') IS NOT NULL")).isEqualTo("t");
    assertThat(pg.query("SELECT count(*) FROM fw_schema_history WHERE module = 'flowcatalyst'")).isEqualTo("1");
  }

  @Test
  void theWorkRunsOnceAndTheRepeatIsSkipped() throws Exception {
    start(r -> {});
    DispatchDelivery d = delivery("dj_1");
    OnceResult<Integer> first = d.once(db, "send-invoice", tx -> send(tx, "dj_1"));
    OnceResult<Integer> second = d.once(db, "send-invoice", tx -> send(tx, "dj_1"));
    assertThat(first).isEqualTo(new OnceResult.Ran<>(1));
    assertThat(second).isEqualTo(new OnceResult.AlreadyDone<>());
    assertThat(sent()).isEqualTo(1);
    assertThat(pg.query("SELECT handler || '/' || job_id FROM fw_dispatch_once")).isEqualTo("send-invoice/dj_1");
  }

  @Test
  void eachHandlerNameAndEachJobIsSeparate() throws Exception {
    start(r -> {});
    assertThat(delivery("dj_1").once(db, "a", tx -> send(tx, "dj_1"))).isInstanceOf(OnceResult.Ran.class);
    assertThat(delivery("dj_1").once(db, "b", tx -> send(tx, "dj_1"))).isInstanceOf(OnceResult.Ran.class);
    assertThat(delivery("dj_2").once(db, "a", tx -> send(tx, "dj_2"))).isInstanceOf(OnceResult.Ran.class);
    assertThat(sent()).isEqualTo(3);
  }

  @Test
  void aFailureRollsBackTheWorkAndTheRecordSoTheRetryRuns() throws Exception {
    start(r -> {});
    DispatchDelivery d = delivery("dj_1");
    Throwable t =
        catchThrowable(
            () ->
                d.once(
                    db,
                    "send-invoice",
                    tx -> {
                      send(tx, "dj_1");
                      throw new IllegalStateException("smtp down");
                    }));
    assertThat(t).isInstanceOf(IllegalStateException.class).hasMessage("smtp down");
    assertThat(sent()).isZero();
    assertThat(pg.query("SELECT count(*) FROM fw_dispatch_once")).isEqualTo("0");
    assertThat(d.once(db, "send-invoice", tx -> send(tx, "dj_1"))).isInstanceOf(OnceResult.Ran.class);
    assertThat(sent()).isEqualTo(1);
  }

  @Test
  void aConcurrentRepeatWaitsForTheFirstAndThenSkips() throws Exception {
    start(r -> {});
    CountDownLatch insideFirst = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CompletableFuture<OnceResult<Integer>> first =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return delivery("dj_1")
                    .once(
                        db,
                        "send-invoice",
                        tx -> {
                          insideFirst.countDown();
                          releaseFirst.await(10, TimeUnit.SECONDS);
                          return send(tx, "dj_1");
                        });
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    assertThat(insideFirst.await(10, TimeUnit.SECONDS)).isTrue();
    AtomicInteger secondRan = new AtomicInteger();
    CompletableFuture<OnceResult<Integer>> second =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return delivery("dj_1")
                    .once(
                        db,
                        "send-invoice",
                        tx -> {
                          secondRan.incrementAndGet();
                          return send(tx, "dj_1");
                        });
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    Thread.sleep(300);
    assertThat(second).isNotDone(); // blocked on the first's uncommitted record
    releaseFirst.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS)).isInstanceOf(OnceResult.Ran.class);
    assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(new OnceResult.AlreadyDone<>());
    assertThat(secondRan).hasValue(0);
    assertThat(sent()).isEqualTo(1);
  }

  @Test
  void aConcurrentRepeatRunsWhenTheFirstFails() throws Exception {
    start(r -> {});
    CountDownLatch insideFirst = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CompletableFuture<Throwable> first =
        CompletableFuture.supplyAsync(
            () ->
                catchThrowable(
                    () ->
                        delivery("dj_1")
                            .once(
                                db,
                                "send-invoice",
                                tx -> {
                                  send(tx, "dj_1");
                                  insideFirst.countDown();
                                  releaseFirst.await(10, TimeUnit.SECONDS);
                                  throw new IllegalStateException("first failed");
                                })));
    assertThat(insideFirst.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<OnceResult<Integer>> second =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return delivery("dj_1").once(db, "send-invoice", tx -> send(tx, "dj_1"));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    Thread.sleep(300);
    assertThat(second).isNotDone();
    releaseFirst.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS)).hasMessage("first failed");
    assertThat(second.get(10, TimeUnit.SECONDS)).isInstanceOf(OnceResult.Ran.class);
    assertThat(sent()).isEqualTo(1);
  }

  @Test
  void aMalformedHandlerNameIsRefused() {
    start(r -> {});
    for (String bad : new String[] {"", "has space", "x".repeat(201), "line\nbreak"}) {
      assertThat(catchThrowable(() -> delivery("dj_1").once(db, bad, tx -> 1)))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void endToEndARedeliveredJobIsAckedButHandledOnce() {
    AtomicInteger calls = new AtomicInteger();
    start(
        r ->
            r.onDispatch(
                TYPE,
                (ctx, d) -> {
                  calls.incrementAndGet();
                  d.once(
                      app.get(Databases.class).defaultInstance(),
                      "send-invoice",
                      tx -> send(tx, d.jobId()));
                  return DispatchResult.ack();
                }));
    TestClient client = Receiving.client(app);
    String body = "{\"id\":\"dj_9\",\"type\":\"" + TYPE + "\",\"attemptNumber\":1}";
    for (int i = 0; i < 3; i++) {
      TestResponse r =
          client
              .post("/fc/dispatch")
              .header("X-Dispatch-Job-Id", "dj_9")
              .header("X-Event-Type", TYPE)
              .header("X-FlowCatalyst-Signature", sign(SECRET, TS, body))
              .header("X-FlowCatalyst-Timestamp", TS)
              .body(body)
              .send();
      assertThat(r.status()).isEqualTo(200);
    }
    assertThat(calls).hasValue(3);
    assertThat(sent()).isEqualTo(1);
  }
}
