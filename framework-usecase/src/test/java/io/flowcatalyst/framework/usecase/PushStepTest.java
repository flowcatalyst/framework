package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code Plan.andPush} (push contract section 2): a row of fw_push_outbox written by the runner. */
class PushStepTest {

  /** The push module's table, V0001__create_push_tables.sql. */
  static final String DDL =
      """
      CREATE TABLE fw_push_outbox (
          id         BIGSERIAL PRIMARY KEY,
          topic      VARCHAR(255) NOT NULL,
          type       VARCHAR(100) NOT NULL,
          client     VARCHAR(100),
          data       TEXT NOT NULL,
          created_at TIMESTAMPTZ NOT NULL
      )
      """;

  static final EventType CREATED = new EventType("things", "stock", "thing", "created");
  static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123Z");

  record Create(String id, String name) {}

  static PostgresFixture pg;

  @TempDir Path dir;

  final InstantSource clock = () -> NOW;
  final RecordingSink sink = new RecordingSink();
  final ThingRepository repo = new ThingRepository();

  App app;
  Database db;
  UseCases useCases;
  Events events;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void start() throws IOException {
    pg.reset();
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    app = App.builder().configDirectory(dir).environment(Map.of()).instantSource(clock).module(new DatabaseModule()).start();
    db = app.get(Databases.class).defaultInstance();
    pg.execute(ThingRepository.DDL);
    pg.execute(DDL);
    useCases = new UseCases(db, sink, clock, d -> {});
    events = new Events(clock, app.get(Ids.class));
  }

  @AfterEach
  void stop() {
    app.close();
  }

  Context ctx(ClientId client) {
    return Context.of(client).withPrincipal(new PrincipalId("usr_1"));
  }

  UseCase<Create, ThingEvent> create(java.util.function.BiFunction<Plan<ThingEvent>, Create, Plan<ThingEvent>> more) {
    return UseCase.named("create-thing", Create.class, ThingEvent.class)
        .authorize(Authorize.PUBLIC)
        .execute(
            (c, cmd) -> {
              Thing t = Thing.create(new ThingId(cmd.id()), c.client(), cmd.name(), cmd.id());
              Plan<ThingEvent> plan = Plan.save(t, repo, new ThingEvent(events.metadata(c, CREATED, t.id()), cmd.id(), cmd.name(), ""));
              return more.apply(plan, cmd);
            });
  }

  String pushes() {
    return pg.query(
        "SELECT string_agg(topic || '|' || type || '|' || coalesce(client, '<null>') || '|' || data || '|' || created_at, ';' ORDER BY id) FROM fw_push_outbox");
  }

  static UseCaseError error(UseCaseResult<?> result) {
    assertThat(result).isInstanceOf(UseCaseResult.Failed.class);
    return ((UseCaseResult.Failed<?>) result).error();
  }

  @Test
  void aPushIsWrittenInPlanOrderForTheContextsClientAndIsNotAnEvent() {
    UseCaseResult<ThingEvent> result =
        useCases.run(
            create(
                (plan, cmd) ->
                    plan.andPush(Push.of("test:thing:" + cmd.id(), "thing.creating", Map.of("z", 1, "a", "x")))
                        .andPush(Push.ofJson("test:thing:" + cmd.id(), "thing.created", "{ \"id\" : \"" + cmd.id() + "\" }"))),
            ctx(new ClientId("acme")),
            new Create("t1", "bolt"));
    assertThat(result).isInstanceOf(UseCaseResult.Committed.class);
    assertThat(pushes())
        .startsWith("test:thing:t1|thing.creating|acme|{\"a\":\"x\",\"z\":1}|")
        .contains(";test:thing:t1|thing.created|acme|{\"id\":\"t1\"}|");
    assertThat(pg.query("SELECT count(*) FROM fw_push_outbox")).isEqualTo("2");
    assertThat(sink.events).as("a push is not an event").hasSize(1);
    assertThat(pg.query("SELECT bool_and(created_at = '2026-10-08T10:15:30.123Z'::timestamptz) FROM fw_push_outbox")).isEqualTo("t");
  }

  @Test
  void aPushWrittenBetweenStepsFollowsTheStepItWasAddedAfter() {
    UseCaseResult<ThingEvent> result =
        useCases.run(
            create(
                (plan, cmd) -> {
                  Thing b = Thing.create(new ThingId(cmd.id() + "b"), new ClientId("acme"), "b", cmd.id() + "b");
                  return plan.andPush(Push.of("test:thing:a", "after.first", Map.of()))
                      .andSave(b, repo, new ThingEvent(events.metadata(ctx(new ClientId("acme")), CREATED, b.id()), b.id().value(), "b", ""))
                      .andPush(Push.of("test:thing:b", "after.second", Map.of()));
                }),
            ctx(new ClientId("acme")),
            new Create("t1", "bolt"));
    assertThat(result).isInstanceOf(UseCaseResult.Committed.class);
    assertThat(pg.query("SELECT string_agg(type, ',' ORDER BY id) FROM fw_push_outbox")).isEqualTo("after.first,after.second");
  }

  @Test
  void aPushOfTheRolledBackAttemptIsNeverWritten() {
    sink.failEvents = new IllegalStateException("boom");
    UseCaseError error =
        error(
            useCases.run(
                create((plan, cmd) -> plan.andPush(Push.of("test:thing:t1", "thing.created", Map.of()))), // written first, then rolled back
                ctx(new ClientId("acme")),
                new Create("t1", "bolt")));
    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("OUTBOX");
    assertThat(pg.query("SELECT count(*) FROM fw_push_outbox")).isEqualTo("0");
    assertThat(pg.query("SELECT count(*) FROM thing")).isEqualTo("0");
  }

  @Test
  void aPushForThePlatformClientIsNull() {
    useCases.run(
        create((plan, cmd) -> plan.andPush(Push.of("test:announcements", "news", Map.of("n", 1)))),
        ctx(ClientId.PLATFORM),
        new Create("t1", "bolt"));
    assertThat(pushes()).startsWith("test:announcements|news|<null>|{\"n\":1}|");
  }

  @Test
  void anInvalidPushFailsTheRunAsInvalidPush() {
    // refused when it is built: the exception is thrown inside the execute phase
    UseCaseError built =
        error(
            useCases.run(
                create((plan, cmd) -> plan.andPush(Push.of("Test:thing", "thing.created", Map.of()))),
                ctx(new ClientId("acme")),
                new Create("t1", "bolt")));
    assertThat(built.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(built.code()).isEqualTo("INVALID_PUSH");
    // over push.max-data-bytes, checked by the runner before it writes anything
    useCases.pushMaxDataBytes(12);
    UseCaseError big =
        error(
            useCases.run(
                create((plan, cmd) -> plan.andPush(Push.of("test:thing:t1", "thing.created", Map.of("s", "abcdefgh")))),
                ctx(new ClientId("acme")),
                new Create("t2", "nut")));
    assertThat(big.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(big.code()).isEqualTo("INVALID_PUSH");
    assertThat(pg.query("SELECT count(*) FROM fw_push_outbox")).isEqualTo("0");
    assertThat(pg.query("SELECT count(*) FROM thing")).isEqualTo("0");
    // at the limit it is written
    UseCaseResult<ThingEvent> ok =
        useCases.run(
            create((plan, cmd) -> plan.andPush(Push.of("test:thing:t1", "thing.created", Map.of("s", "ab")))), // {"s":"ab"} is 10 bytes
            ctx(new ClientId("acme")),
            new Create("t3", "nut"));
    assertThat(ok).isInstanceOf(UseCaseResult.Committed.class);
  }

  @Test
  void aFailureWritingAPushIsInternalAtStepPush() {
    pg.execute("DROP TABLE fw_push_outbox");
    UseCaseError error =
        error(
            useCases.run(
                create((plan, cmd) -> plan.andPush(Push.of("test:thing:t1", "thing.created", Map.of()))),
                ctx(new ClientId("acme")),
                new Create("t1", "bolt")));
    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("PUSH");
    assertThat(pg.query("SELECT count(*) FROM thing")).isEqualTo("0");
  }

  @Test
  void pushLimitsAreCheckedWhenTheRunnerIsConfigured() {
    assertThatThrownBy(() -> useCases.pushMaxDataBytes(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> useCases.pushMaxDataBytes(Push.MAX_DATA_BYTES + 1)).isInstanceOf(IllegalArgumentException.class);
  }
}
