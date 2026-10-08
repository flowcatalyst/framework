package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ApplicationCode;
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
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The runner against a real PostgreSQL, with a recording sink standing in for the outbox. */
class RunnerTest {

  static final EventType THING_CREATED = new EventType("things", "stock", "thing", "created");
  static final EventType THING_CHANGED = new EventType("things", "stock", "thing", "changed");
  static final EventType THING_REMOVED = new EventType("things", "stock", "thing", "removed");
  static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123Z");

  record CreateThing(String id, String name, String code) {}

  record Edit(String id, String name, String code) {}

  record Remove(String id) {}

  record Audited(String id, String secretToLeaveOut) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("id", id);
    }
  }

  static PostgresFixture pg;

  @TempDir Path dir;

  final ClientId acme = new ClientId("acme");
  final ClientId other = new ClientId("other");
  final InstantSource clock = () -> NOW;
  final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
  final RecordingSink sink = new RecordingSink();
  final ThingRepository repo = new ThingRepository();
  final AtomicInteger runs = new AtomicInteger();
  final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

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
    app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .instantSource(clock)
            .module(new DatabaseModule())
            .start();
    db = app.get(Databases.class).defaultInstance();
    pg.execute(ThingRepository.DDL);
    useCases = new UseCases(db, sink, clock, sleeps::add);
    events = new Events(clock, app.get(Ids.class));
  }

  @AfterEach
  void stop() {
    threads.close();
    app.close();
  }

  // ---- helpers -----------------------------------------------------------------------------

  Context ctx(ClientId client) {
    return Context.of(client).withPrincipal(new PrincipalId("usr_1"));
  }

  ThingEvent eventFor(Context c, EventType type, Thing t) {
    return new ThingEvent(events.metadata(c, type, t.id()), t.id().value(), t.name(), t.code());
  }

  UseCase<CreateThing, ThingEvent> create(ConflictPolicy policy) {
    return UseCase.named("create-thing", CreateThing.class, ThingEvent.class)
        .authorize(Authorize.PUBLIC)
        .conflict(policy)
        .execute(
            (c, cmd) -> {
              runs.incrementAndGet();
              Thing t = Thing.create(new ThingId(cmd.id()), c.client(), cmd.name(), cmd.code());
              return Plan.save(t, repo, eventFor(c, THING_CREATED, t));
            });
  }

  /** Loads the thing, runs {@code afterLoad}, applies the changes. */
  UseCase<Edit, ThingEvent> edit(ConflictPolicy policy, Gate afterLoad) {
    return UseCase.named("edit-thing", Edit.class, ThingEvent.class)
        .authorize(Authorize.PUBLIC)
        .conflict(policy)
        .execute(
            (c, cmd) -> {
              runs.incrementAndGet();
              Optional<Thing> found = repo.find(db, c.client(), new ThingId(cmd.id()));
              if (found.isEmpty()) {
                return Decision.reject(UseCaseError.notFound("THING_NOT_FOUND", "no such thing"));
              }
              Thing t = found.get();
              afterLoad.reached();
              if (cmd.name() != null) {
                t = t.withName(cmd.name());
              }
              if (cmd.code() != null) {
                t = t.withCode(cmd.code());
              }
              return Plan.save(t, repo, eventFor(c, THING_CHANGED, t));
            });
  }

  UseCase<Remove, ThingEvent> remove(ConflictPolicy policy, Gate afterLoad) {
    return UseCase.named("remove-thing", Remove.class, ThingEvent.class)
        .authorize(Authorize.PUBLIC)
        .conflict(policy)
        .execute(
            (c, cmd) -> {
              runs.incrementAndGet();
              Optional<Thing> found = repo.find(db, c.client(), new ThingId(cmd.id()));
              if (found.isEmpty()) {
                return Decision.reject(UseCaseError.notFound("THING_NOT_FOUND", "no such thing"));
              }
              afterLoad.reached();
              return Plan.delete(found.get(), repo, eventFor(c, THING_REMOVED, found.get()));
            });
  }

  /** Something an execute phase does after it has loaded. */
  @FunctionalInterface
  interface Gate {
    Gate NONE = () -> {};

    void reached() throws Exception;
  }

  /** Holds the first attempt after its load until the test lets it go. */
  static final class Interleave implements Gate {
    final CountDownLatch loaded = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);

    @Override
    public void reached() throws Exception {
      if (first.getAndSet(false)) {
        loaded.countDown();
        if (!proceed.await(20, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test never released the first attempt");
        }
      }
    }

    void awaitLoaded() throws InterruptedException {
      assertThat(loaded.await(20, TimeUnit.SECONDS)).isTrue();
    }

    void release() {
      proceed.countDown();
    }
  }

  void seed(String id, String name, String code) {
    UseCaseResult<ThingEvent> r =
        useCases.run(create(ConflictPolicy.fail()), ctx(acme), new CreateThing(id, name, code));
    assertThat(r).isInstanceOf(UseCaseResult.Committed.class);
    runs.set(0);
    sink.events.clear();
    sink.audits.clear();
  }

  String row(String id) {
    return pg.query(
        "SELECT version || ':' || name || ':' || coalesce(code, '-') FROM thing WHERE id = '"
            + id
            + "'");
  }

  int thingCount() {
    return Integer.parseInt(pg.query("SELECT count(*) FROM thing"));
  }

  static UseCaseError error(UseCaseResult<?> result) {
    assertThat(result).isInstanceOf(UseCaseResult.Failed.class);
    return ((UseCaseResult.Failed<?>) result).error();
  }

  static ThingEvent committed(UseCaseResult<ThingEvent> result) {
    assertThat(result).isInstanceOf(UseCaseResult.Committed.class);
    return ((UseCaseResult.Committed<ThingEvent>) result).event();
  }

  // ---- the happy path ----------------------------------------------------------------------

  @Test
  void aCreateStoresVersionOneAndReturnsTheEvent() {
    UseCaseResult<ThingEvent> result =
        useCases.run(create(ConflictPolicy.fail()), ctx(acme), new CreateThing("t1", "bolt", "B-1"));

    ThingEvent event = committed(result);
    assertThat(event.thingId()).isEqualTo("t1");
    assertThat(row("t1")).isEqualTo("1:bolt:B-1");
    assertThat(sink.events).containsExactly(event);
    assertThat(sink.audits).hasSize(1);
  }

  @Test
  void eventMetadataComesFromTheContextAndTheInjectedClock() {
    Context c =
        ctx(acme)
            .withApplication(new ApplicationCode("things"))
            .withCausationId("evt_cause")
            .withExecutionId("job_9");
    ThingEvent event =
        committed(useCases.run(create(ConflictPolicy.fail()), c, new CreateThing("t1", "n", "c")));

    EventMetadata m = event.metadata();
    assertThat(m.eventId()).matches("[0-9A-HJKMNP-TV-Z]{13}");
    assertThat(m.specVersion()).isEqualTo("1.0");
    assertThat(m.type()).isEqualTo("things:stock:thing:created");
    assertThat(m.source()).isEqualTo("things:stock");
    assertThat(m.subject()).isEqualTo("stock.thing.t1");
    assertThat(m.messageGroup()).isEqualTo("stock:thing:t1");
    assertThat(m.time()).isEqualTo(NOW);
    assertThat(m.correlationId()).isEqualTo(c.correlationId().value());
    assertThat(m.principalId()).isEqualTo("usr_1");
    assertThat(m.causationId()).isEqualTo("evt_cause");
    assertThat(m.executionId()).isEqualTo("job_9");
  }

  @Test
  void theAuditEntryNamesTheAggregateTheOperationAndTheContext() {
    Context c = ctx(acme).withApplication(new ApplicationCode("things"));
    UseCase<Audited, ThingEvent> useCase =
        UseCase.named("audited-create", Audited.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (cx, cmd) -> {
                  Thing t = Thing.create(new ThingId(cmd.id()), cx.client(), "n", "c");
                  return Plan.save(t, repo, eventFor(cx, THING_CREATED, t));
                });

    useCases.run(useCase, c, new Audited("t1", "hunter2"));

    AuditEntry audit = sink.audits.getFirst();
    assertThat(audit.entityType()).isEqualTo("Thing");
    assertThat(audit.entityId()).isEqualTo("t1");
    assertThat(audit.operation()).isEqualTo("audited-create");
    assertThat(audit.data()).isEqualTo(Map.of("id", "t1"));
    assertThat(audit.principalId()).isEqualTo("usr_1");
    assertThat(audit.performedAt()).isEqualTo(NOW);
    assertThat(audit.source()).isEqualTo("things:stock");
    assertThat(audit.correlationId()).isEqualTo(c.correlationId().value());
    assertThat(audit.applicationCode()).isEqualTo("things");
    assertThat(audit.client()).isEqualTo(acme);
  }

  @Test
  void commandsThatAreNotAuditDataRecordNoData() {
    useCases.run(create(ConflictPolicy.fail()), ctx(acme), new CreateThing("t1", "n", "c"));
    assertThat(sink.audits.getFirst().data()).isNull();
  }

  @Test
  void aPlanWithOnlyAnEventWritesNoAggregateAndTakesTheEntityFromTheSubject() {
    UseCase<Remove, ThingEvent> ping =
        UseCase.named("ping-thing", Remove.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing t = Thing.create(new ThingId(cmd.id()), c.client(), "n", "c");
                  return Plan.emit(eventFor(c, THING_CHANGED, t));
                });

    committed(useCases.run(ping, ctx(acme), new Remove("t.9")));

    assertThat(thingCount()).isZero();
    AuditEntry audit = sink.audits.getFirst();
    assertThat(audit.entityType()).isEqualTo("thing");
    assertThat(audit.entityId()).isEqualTo("t.9");
  }

  @Test
  void aPlanCanSaveSeveralAggregatesAndWritesEveryEventInOrder() {
    UseCase<CreateThing, ThingEvent> two =
        UseCase.named("create-two", CreateThing.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing a = Thing.create(new ThingId(cmd.id() + "a"), c.client(), "a", "a");
                  Thing b = Thing.create(new ThingId(cmd.id() + "b"), c.client(), "b", "b");
                  return Plan.save(a, repo, eventFor(c, THING_CREATED, a))
                      .andSave(b, repo, eventFor(c, THING_CREATED, b));
                });

    ThingEvent result = committed(useCases.run(two, ctx(acme), new CreateThing("t", "", "")));

    assertThat(result.thingId()).isEqualTo("ta");
    assertThat(sink.events).extracting(e -> ((ThingEvent) e).thingId()).containsExactly("ta", "tb");
    assertThat(thingCount()).isEqualTo(2);
  }

  // ---- phases ------------------------------------------------------------------------------

  @Test
  void aValidationErrorIsTheResultAndNothingElseRuns() {
    AtomicInteger later = new AtomicInteger();
    UseCase<CreateThing, ThingEvent> useCase =
        UseCase.named("create-thing", CreateThing.class, ThingEvent.class)
            .validate((c, cmd) -> Optional.of(UseCaseError.validation("NAME_REQUIRED", "name?")))
            .authorize(
                (c, cmd) -> {
                  later.incrementAndGet();
                  return Optional.empty();
                })
            .execute(
                (c, cmd) -> {
                  later.incrementAndGet();
                  return Decision.reject(UseCaseError.internal("X"));
                });

    UseCaseError error = error(useCases.run(useCase, ctx(acme), new CreateThing("t", "", "")));

    assertThat(error.kind()).isEqualTo(ErrorKind.VALIDATION);
    assertThat(error.code()).isEqualTo("NAME_REQUIRED");
    assertThat(later).hasValue(0);
    assertThat(sink.events).isEmpty();
  }

  @Test
  void anAuthorizationErrorStopsBeforeExecute() {
    UseCase<CreateThing, ThingEvent> useCase =
        UseCase.named("create-thing", CreateThing.class, ThingEvent.class)
            .authorize((c, cmd) -> Optional.of(UseCaseError.authorization("FORBIDDEN", "no")))
            .execute(
                (c, cmd) -> {
                  runs.incrementAndGet();
                  return Decision.reject(UseCaseError.internal("X"));
                });

    UseCaseError error = error(useCases.run(useCase, ctx(acme), new CreateThing("t", "", "")));

    assertThat(error.kind()).isEqualTo(ErrorKind.AUTHORIZATION);
    assertThat(runs).hasValue(0);
  }

  @Test
  void aRejectedDecisionIsTheResultAndWritesNothing() {
    UseCaseError error =
        error(
            useCases.run(
                edit(ConflictPolicy.fail(), Gate.NONE), ctx(acme), new Edit("missing", "x", null)));

    assertThat(error.kind()).isEqualTo(ErrorKind.NOT_FOUND);
    assertThat(error.code()).isEqualTo("THING_NOT_FOUND");
    assertThat(sink.events).isEmpty();
    assertThat(sink.audits).isEmpty();
  }

  @Test
  void aThrowingPhaseIsInternalAndNeverLeaksTheCauseMessage() {
    for (String phase : new String[] {"VALIDATE", "AUTHORIZE", "EXECUTE"}) {
      UseCase.Builder<CreateThing, ThingEvent> b =
          UseCase.named("create-thing", CreateThing.class, ThingEvent.class);
      if (phase.equals("VALIDATE")) {
        b.validate(
            (c, cmd) -> {
              throw new IllegalStateException("password=hunter2");
            });
      }
      b.authorize(
          (c, cmd) -> {
            if (phase.equals("AUTHORIZE")) {
              throw new IllegalStateException("password=hunter2");
            }
            return Optional.empty();
          });
      UseCase<CreateThing, ThingEvent> useCase =
          b.execute(
              (c, cmd) -> {
                throw new SQLException("password=hunter2 at 10.0.0.5", "XX000");
              });

      UseCaseError error = error(useCases.run(useCase, ctx(acme), new CreateThing("t", "", "")));

      assertThat(error.kind()).as(phase).isEqualTo(ErrorKind.INTERNAL);
      assertThat(error.code()).as(phase).isEqualTo(phase);
      assertThat(error.message()).doesNotContain("hunter2").doesNotContain("10.0.0.5");
      assertThat(error.details()).isEmpty();
    }
  }

  // ---- client scoping ----------------------------------------------------------------------

  @Test
  void aPlanWithAnotherClientsAggregateFailsAndWritesNothing() {
    UseCase<CreateThing, ThingEvent> useCase =
        UseCase.named("create-crossing", CreateThing.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing mine = Thing.create(new ThingId("mine"), c.client(), "m", "m");
                  Thing theirs = Thing.create(new ThingId("theirs"), other, "t", "t");
                  return Plan.save(mine, repo, eventFor(c, THING_CREATED, mine))
                      .andSave(theirs, repo, eventFor(c, THING_CREATED, theirs));
                });

    UseCaseError error = error(useCases.run(useCase, ctx(acme), new CreateThing("x", "", "")));

    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("CROSS_CLIENT_WRITE");
    assertThat(thingCount()).isZero();
    assertThat(sink.events).isEmpty();
    assertThat(sink.audits).isEmpty();
  }

  @Test
  void aCrossClientDeleteIsAlsoRefused() {
    seed("t1", "bolt", "B-1");
    // load as acme, then try to delete it while acting as another client
    Thing mine = repo_find("t1");
    UseCase<Remove, ThingEvent> useCase =
        UseCase.named("remove-foreign", Remove.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute((c, cmd) -> Plan.delete(mine, repo, eventFor(c, THING_REMOVED, mine)));

    UseCaseError error = error(useCases.run(useCase, ctx(other), new Remove("t1")));

    assertThat(error.code()).isEqualTo("CROSS_CLIENT_WRITE");
    assertThat(row("t1")).isEqualTo("1:bolt:B-1");
  }

  @Test
  void findFiltersByClient() throws Exception {
    seed("t1", "bolt", "B-1");
    assertThat(repo.find(db, other, new ThingId("t1"))).isEmpty();
    assertThat(repo.find(db, acme, new ThingId("t1"))).isPresent();
  }

  Thing repo_find(String id) {
    try {
      return repo.find(db, acme, new ThingId(id)).orElseThrow();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---- optimistic locking ------------------------------------------------------------------

  @Test
  void aStaleUpdateFailsUnderFailPolicy() throws Exception {
    seed("t1", "bolt", "B-1");
    Interleave gate = new Interleave();

    Future<UseCaseResult<ThingEvent>> a =
        threads.submit(
            () ->
                useCases.run(
                    edit(ConflictPolicy.fail(), gate), ctx(acme), new Edit("t1", "washer", null)));
    gate.awaitLoaded();
    committed(
        useCases.run(edit(ConflictPolicy.fail(), Gate.NONE), ctx(acme), new Edit("t1", null, "B-2")));
    gate.release();

    UseCaseError error = error(a.get(20, TimeUnit.SECONDS));
    assertThat(error.kind()).isEqualTo(ErrorKind.CONFLICT);
    assertThat(error.code()).isEqualTo("STALE_AGGREGATE");
    assertThat(row("t1")).isEqualTo("2:bolt:B-2");
    assertThat(sink.events).hasSize(1);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void aStaleUpdateUnderRetryRunsAgainFromTheStartAndCommitsOnTopOfTheOtherChange()
      throws Exception {
    seed("t1", "bolt", "B-1");
    Interleave gate = new Interleave();
    ConflictPolicy retry = ConflictPolicy.retry(3, Duration.ofMillis(100));

    Future<UseCaseResult<ThingEvent>> a =
        threads.submit(
            () -> useCases.run(edit(retry, gate), ctx(acme), new Edit("t1", "washer", null)));
    gate.awaitLoaded();
    committed(
        useCases.run(edit(ConflictPolicy.fail(), Gate.NONE), ctx(acme), new Edit("t1", null, "B-2")));
    gate.release();

    committed(a.get(20, TimeUnit.SECONDS));
    assertThat(row("t1")).isEqualTo("3:washer:B-2");
    assertThat(runs).hasValue(3); // A twice, B once
    assertThat(sleeps).hasSize(1);
    assertThat(sleeps.getFirst()).isBetween(Duration.ofMillis(50), Duration.ofMillis(150));
    assertThat(sink.events).hasSize(2); // B's and A's second attempt: the failed attempt wrote none
  }

  @Test
  void aStaleDeleteFailsUnderFailPolicyAndLeavesTheRow() throws Exception {
    seed("t1", "bolt", "B-1");
    Interleave gate = new Interleave();

    Future<UseCaseResult<ThingEvent>> a =
        threads.submit(
            () ->
                useCases.run(remove(ConflictPolicy.fail(), gate), ctx(acme), new Remove("t1")));
    gate.awaitLoaded();
    committed(
        useCases.run(edit(ConflictPolicy.fail(), Gate.NONE), ctx(acme), new Edit("t1", "x", null)));
    gate.release();

    assertThat(error(a.get(20, TimeUnit.SECONDS)).code()).isEqualTo("STALE_AGGREGATE");
    assertThat(row("t1")).isEqualTo("2:x:B-1");
  }

  @Test
  void aStaleDeleteUnderRetryReloadsAndThenDeletes() throws Exception {
    seed("t1", "bolt", "B-1");
    Interleave gate = new Interleave();

    Future<UseCaseResult<ThingEvent>> a =
        threads.submit(
            () ->
                useCases.run(
                    remove(ConflictPolicy.retry(2, Duration.ZERO), gate),
                    ctx(acme),
                    new Remove("t1")));
    gate.awaitLoaded();
    committed(
        useCases.run(edit(ConflictPolicy.fail(), Gate.NONE), ctx(acme), new Edit("t1", "x", null)));
    gate.release();

    committed(a.get(20, TimeUnit.SECONDS));
    assertThat(thingCount()).isZero();
  }

  @Test
  void aRetriedDeleteWhoseRowIsGoneIsRejectedNotFoundOnTheSecondAttempt() throws Exception {
    seed("t1", "bolt", "B-1");
    Interleave gate = new Interleave();

    Future<UseCaseResult<ThingEvent>> a =
        threads.submit(
            () ->
                useCases.run(
                    remove(ConflictPolicy.retry(2, Duration.ZERO), gate),
                    ctx(acme),
                    new Remove("t1")));
    gate.awaitLoaded();
    committed(useCases.run(remove(ConflictPolicy.fail(), Gate.NONE), ctx(acme), new Remove("t1")));
    gate.release();

    assertThat(error(a.get(20, TimeUnit.SECONDS)).code()).isEqualTo("THING_NOT_FOUND");
  }

  @Test
  void exhaustedRetriesEndAsStaleAggregate() {
    seed("t1", "bolt", "B-1");
    // Every attempt is overtaken: after loading, a direct write bumps the version.
    Gate overtaken = () -> pg.execute("UPDATE thing SET version = version + 1 WHERE id = 't1'");

    UseCaseError error =
        error(
            useCases.run(
                edit(ConflictPolicy.retry(3, Duration.ofMillis(10)), overtaken),
                ctx(acme),
                new Edit("t1", "washer", null)));

    assertThat(error.kind()).isEqualTo(ErrorKind.CONFLICT);
    assertThat(error.code()).isEqualTo("STALE_AGGREGATE");
    assertThat(runs).hasValue(3);
    assertThat(sleeps).hasSize(2);
    assertThat(row("t1")).isEqualTo("4:bolt:B-1");
    assertThat(sink.events).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"40001", "40P01"})
  void serializationFailuresAndDeadlocksAreConflictsHandledByThePolicy(String sqlState) {
    seed("t1", "bolt", "B-1");
    AtomicInteger calls = new AtomicInteger();
    repo.beforeUpdate =
        thing -> {
          if (calls.incrementAndGet() == 1) {
            throw new SQLException("simulated", sqlState);
          }
        };

    UseCaseError failing =
        error(
            useCases.run(
                edit(ConflictPolicy.fail(), Gate.NONE), ctx(acme), new Edit("t1", "washer", null)));
    assertThat(failing.kind()).isEqualTo(ErrorKind.CONFLICT);
    assertThat(failing.code()).isEqualTo("STALE_AGGREGATE");
    assertThat(row("t1")).isEqualTo("1:bolt:B-1");

    calls.set(0);
    committed(
        useCases.run(
            edit(ConflictPolicy.retry(2, Duration.ZERO), Gate.NONE),
            ctx(acme),
            new Edit("t1", "washer", null)));
    assertThat(calls).hasValue(2);
    assertThat(row("t1")).isEqualTo("2:washer:B-1");
  }

  @Test
  void aRealDeadlockIsRetriedAndBothUseCasesCommit() throws Exception {
    seed("x", "x", "X");
    seed("y", "y", "Y");
    CountDownLatch bothHoldTheirFirstRow = new CountDownLatch(2);
    repo.afterUpdate =
        thing -> {
          bothHoldTheirFirstRow.countDown();
          try {
            bothHoldTheirFirstRow.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    ConflictPolicy retry = ConflictPolicy.retry(3, Duration.ZERO);
    UseCase<Edit, ThingEvent> xThenY = twoThings("x-then-y", "x", "y", retry);
    UseCase<Edit, ThingEvent> yThenX = twoThings("y-then-x", "y", "x", retry);

    Future<UseCaseResult<ThingEvent>> a =
        threads.submit(() -> useCases.run(xThenY, ctx(acme), new Edit("-", "n1", null)));
    Future<UseCaseResult<ThingEvent>> b =
        threads.submit(() -> useCases.run(yThenX, ctx(acme), new Edit("-", "n2", null)));

    committed(a.get(60, TimeUnit.SECONDS));
    committed(b.get(60, TimeUnit.SECONDS));
    assertThat(runs).hasValue(3); // one of them was the deadlock victim and ran twice
    assertThat(pg.query("SELECT version FROM thing WHERE id = 'x'")).isEqualTo("3");
    assertThat(pg.query("SELECT version FROM thing WHERE id = 'y'")).isEqualTo("3");
  }

  UseCase<Edit, ThingEvent> twoThings(String name, String first, String second, ConflictPolicy p) {
    return UseCase.named(name, Edit.class, ThingEvent.class)
        .authorize(Authorize.PUBLIC)
        .conflict(p)
        .execute(
            (c, cmd) -> {
              runs.incrementAndGet();
              Thing a = repo.find(db, c.client(), new ThingId(first)).orElseThrow();
              Thing b = repo.find(db, c.client(), new ThingId(second)).orElseThrow();
              Thing a2 = a.withName(cmd.name());
              Thing b2 = b.withName(cmd.name());
              return Plan.save(a2, repo, eventFor(c, THING_CHANGED, a2))
                  .andSave(b2, repo, eventFor(c, THING_CHANGED, b2));
            });
  }

  // ---- unique violations -------------------------------------------------------------------

  @Test
  void aUniqueViolationNamesTheConstraintAndIsNotRetried() {
    seed("t1", "bolt", "B-1");

    UseCaseError error =
        error(
            useCases.run(
                create(ConflictPolicy.retry(5, Duration.ZERO)),
                ctx(acme),
                new CreateThing("t2", "nut", "B-1")));

    assertThat(error.kind()).isEqualTo(ErrorKind.CONFLICT);
    assertThat(error.code()).isEqualTo("UNIQUE_VIOLATION");
    assertThat(error.details()).containsEntry("constraint", "thing_code_key");
    assertThat(error.message()).doesNotContain("B-1");
    assertThat(runs).hasValue(1);
    assertThat(sleeps).isEmpty();
    assertThat(thingCount()).isEqualTo(1);
  }

  // ---- rollback ----------------------------------------------------------------------------

  @Test
  void aFailureAfterTheFirstWriteRollsTheFirstWriteBack() {
    seed("t1", "bolt", "B-1");
    UseCase<CreateThing, ThingEvent> useCase =
        UseCase.named("create-then-clash", CreateThing.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing fine = Thing.create(new ThingId("fine"), c.client(), "f", "F");
                  Thing clash = Thing.create(new ThingId("clash"), c.client(), "c", "B-1");
                  return Plan.save(fine, repo, eventFor(c, THING_CREATED, fine))
                      .andSave(clash, repo, eventFor(c, THING_CREATED, clash));
                });

    UseCaseError error = error(useCases.run(useCase, ctx(acme), new CreateThing("-", "", "")));

    assertThat(error.code()).isEqualTo("UNIQUE_VIOLATION");
    assertThat(thingCount()).isEqualTo(1);
    assertThat(sink.events).isEmpty();
  }

  @Test
  void anEventWriteFailureRollsEverythingBackAndIsReportedAsOutbox() {
    sink.failEvents = new IllegalStateException("disk full at /var/secret");

    UseCaseError error =
        error(
            useCases.run(
                create(ConflictPolicy.fail()), ctx(acme), new CreateThing("t1", "bolt", "B-1")));

    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("OUTBOX");
    assertThat(error.message()).doesNotContain("secret");
    assertThat(thingCount()).isZero();
  }

  @Test
  void anAuditWriteFailureRollsEverythingBackAndIsReportedAsAudit() {
    sink.failAudit = new IllegalStateException("nope");

    UseCaseError error =
        error(
            useCases.run(
                create(ConflictPolicy.fail()), ctx(acme), new CreateThing("t1", "bolt", "B-1")));

    assertThat(error.code()).isEqualTo("AUDIT");
    assertThat(thingCount()).isZero();
  }

  @Test
  void anAuditDataMethodThatThrowsFailsTheAuditStep() {
    record Boom(String id) implements AuditData {
      @Override
      public Map<String, Object> auditData() {
        throw new IllegalStateException("secret");
      }
    }
    UseCase<Boom, ThingEvent> boom =
        UseCase.named("boom", Boom.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (cx, cmd) -> {
                  Thing t = Thing.create(new ThingId(cmd.id()), cx.client(), "n", "c");
                  return Plan.save(t, repo, eventFor(cx, THING_CREATED, t));
                });

    UseCaseError error = error(useCases.run(boom, ctx(acme), new Boom("t1")));

    assertThat(error.code()).isEqualTo("AUDIT");
    assertThat(thingCount()).isZero();
  }

  @Test
  void aRepositoryThatTouchesMoreThanOneRowIsABug() {
    seed("t1", "bolt", "B-1");
    repo.updateCount = thing -> 2;

    UseCaseError error =
        error(
            useCases.run(
                edit(ConflictPolicy.retry(3, Duration.ZERO), Gate.NONE),
                ctx(acme),
                new Edit("t1", "washer", null)));

    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("PERSIST");
    assertThat(runs).hasValue(1); // not retried
    assertThat(row("t1")).isEqualTo("1:bolt:B-1");
  }

  @Test
  void deletingAnAggregateThatWasNeverStoredIsABug() {
    UseCase<Remove, ThingEvent> useCase =
        UseCase.named("remove-new", Remove.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing t = Thing.create(new ThingId("n"), c.client(), "n", "n");
                  return Plan.delete(t, repo, eventFor(c, THING_REMOVED, t));
                });

    assertThat(error(useCases.run(useCase, ctx(acme), new Remove("n"))).code()).isEqualTo("PERSIST");
  }

  @Test
  void anInterruptedWaitBetweenAttemptsEndsTheRun() {
    seed("t1", "bolt", "B-1");
    UseCases interrupted =
        new UseCases(
            db,
            sink,
            clock,
            d -> {
              throw new InterruptedException();
            });
    Gate overtaken = () -> pg.execute("UPDATE thing SET version = version + 1 WHERE id = 't1'");

    UseCaseError error =
        error(
            interrupted.run(
                edit(ConflictPolicy.retry(3, Duration.ofMillis(10)), overtaken),
                ctx(acme),
                new Edit("t1", "washer", null)));

    assertThat(error.code()).isEqualTo("INTERRUPTED");
    assertThat(Thread.interrupted()).isTrue(); // the flag was restored; clear it for later tests
  }
}
