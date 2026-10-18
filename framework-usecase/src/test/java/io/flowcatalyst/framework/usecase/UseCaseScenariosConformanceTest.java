package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.JsonVectors;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.ClientId;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes and checks {@code conformance/usecase/scenarios.json} (write-path contract sections 3a, 4,
 * 4a and 5): a use case described by which phases fail and how, a prior database state, a command and
 * a context go in; the result (kind, code, details, HTTP status), the phase trace, the events and
 * audit rows the sink received and the rows in the aggregate table come out. Java writes it; Go compares.
 */
class UseCaseScenariosConformanceTest {

  static final String FILE = "usecase/scenarios.json";
  static final EventType CREATED = new EventType("things", "stock", "thing", "created");
  static final EventType CHANGED = new EventType("things", "stock", "thing", "changed");
  static final EventType REMOVED = new EventType("things", "stock", "thing", "removed");
  static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123Z");
  static final String COMMIT_TRIGGER =
      """
      CREATE FUNCTION thing_commit_check() RETURNS trigger AS $$
      BEGIN
        IF NEW.name = 'commit-fails' THEN RAISE EXCEPTION 'deferred check failed'; END IF;
        RETURN NULL;
      END $$ LANGUAGE plpgsql;
      CREATE CONSTRAINT TRIGGER thing_commit_check AFTER INSERT OR UPDATE ON thing
        DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION thing_commit_check();
      """;

  record Cmd(String op, String id, String name, String code) {}

  static PostgresFixture pg;

  @TempDir Path dir;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  // ---- the scenario as data ----------------------------------------------------------------

  /** The use case's description. */
  record Uc(
      String validate,
      String authorize,
      String load,
      String authorizeLoaded,
      String execute,
      String conflict,
      int maxAttempts) {}

  /** A fault injected into an attempt. kind: concurrentWrite | sqlState | persistError | events | audit | commit | begin | interrupt. */
  record Fault(String kind, String state, List<Integer> attempts) {}

  /** The caller's transaction for a joined run. */
  record Join(boolean callerInsert, String end, boolean autoCommit, Cmd followUp) {}

  record Scenario(
      String name,
      List<String> prior,
      Uc uc,
      Cmd command,
      String client,
      List<Fault> faults,
      Join join) {}

  static Uc uc(String execute) {
    return new Uc("ok", "ok", "none", "none", execute, "fail", 0);
  }

  static final class S {
    final String name;
    final List<String> prior = new ArrayList<>();
    Uc uc = UseCaseScenariosConformanceTest.uc("edit");
    Cmd cmd = new Cmd("edit", "t1", "washer", null);
    String client = "acme";
    final List<Fault> faults = new ArrayList<>();
    Join join = null;

    S(String name) {
      this.name = name;
    }

    S prior(String... rows) {
      prior.addAll(List.of(rows));
      return this;
    }

    S uc(Uc u) {
      uc = u;
      return this;
    }

    S cmd(Cmd c) {
      cmd = c;
      return this;
    }

    S client(String c) {
      client = c;
      return this;
    }

    S fault(String kind, String state, Integer... attempts) {
      faults.add(new Fault(kind, state, List.of(attempts)));
      return this;
    }

    S join(Join j) {
      join = j;
      return this;
    }

    Scenario build() {
      return new Scenario(name, prior, uc, cmd, client, faults, join);
    }
  }

  static Uc with(Uc u, String field, String value) {
    return switch (field) {
      case "validate" -> new Uc(value, u.authorize, u.load, u.authorizeLoaded, u.execute, u.conflict, u.maxAttempts);
      case "authorize" -> new Uc(u.validate, value, u.load, u.authorizeLoaded, u.execute, u.conflict, u.maxAttempts);
      case "load" -> new Uc(u.validate, u.authorize, value, u.authorizeLoaded, u.execute, u.conflict, u.maxAttempts);
      case "authorizeLoaded" -> new Uc(u.validate, u.authorize, u.load, value, u.execute, u.conflict, u.maxAttempts);
      case "execute" -> new Uc(u.validate, u.authorize, u.load, u.authorizeLoaded, value, u.conflict, u.maxAttempts);
      default -> throw new IllegalArgumentException(field);
    };
  }

  static Uc retry(Uc u, int max) {
    return new Uc(u.validate, u.authorize, u.load, u.authorizeLoaded, u.execute, "retry", max);
  }

  static Uc loaded(String check) {
    return new Uc("ok", "ok", "ok", check, "edit", "fail", 0);
  }

  static final String T1 = "t1|acme|1|bolt|B-1";

  static List<Scenario> scenarios() {
    List<Scenario> s = new ArrayList<>();
    // happy paths
    s.add(new S("create commits version 1, one event, one audit row").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).build());
    s.add(new S("edit commits version 2").prior(T1).build());
    s.add(new S("remove deletes the row").prior(T1).uc(uc("remove")).cmd(new Cmd("remove", "t1", null, null)).build());
    s.add(new S("a plan with only an event writes no aggregate").prior(T1).uc(uc("emit")).cmd(new Cmd("emit", "t1", null, null)).build());
    // 1 validate
    s.add(new S("validate refuses").prior(T1).uc(with(uc("edit"), "validate", "refuse")).build());
    s.add(new S("validate throws").prior(T1).uc(with(uc("edit"), "validate", "throw")).build());
    // 2 authorize
    s.add(new S("authorize refuses").prior(T1).uc(with(uc("edit"), "authorize", "refuse")).build());
    s.add(new S("authorize throws").prior(T1).uc(with(uc("edit"), "authorize", "throw")).build());
    s.add(new S("execute throws").prior(T1).uc(with(uc("edit"), "execute", "throw")).build());
    s.add(new S("execute rejects").prior(T1).uc(with(uc("edit"), "execute", "reject")).build());
    // 3 load phase
    s.add(new S("load phase: order is validate, authorize, load, authorizeLoaded, execute").prior(T1).uc(loaded("ok")).build());
    s.add(new S("load phase without a record check").prior(T1).uc(loaded("none")).build());
    s.add(new S("load phase: authorize refuses so nothing is loaded").prior(T1).uc(with(loaded("ok"), "authorize", "refuse")).build());
    s.add(new S("load phase: validate refuses so nothing is loaded").prior(T1).uc(with(loaded("ok"), "validate", "refuse")).build());
    s.add(new S("load phase: the record is missing").uc(loaded("ok")).build());
    s.add(new S("load phase: another client's record is missing").prior("t1|other|1|bolt|B-1").uc(loaded("ok")).build());
    s.add(new S("load phase: authorizeLoaded refuses").prior(T1).uc(loaded("refuse")).build());
    s.add(new S("load phase: authorizeLoaded answers whenMissing to hide existence").prior(T1).uc(loaded("hide")).build());
    s.add(new S("load phase: load throws").prior(T1).uc(with(loaded("ok"), "load", "throw")).build());
    s.add(new S("load phase: authorizeLoaded throws").prior(T1).uc(loaded("throw")).build());
    s.add(new S("load phase: a record the check refuses by its content").prior("t1|acme|1|locked|B-1").uc(loaded("ok")).build());
    // 4 conflicts
    s.add(new S("stale aggregate with fail").prior(T1).fault("concurrentWrite", null, 1).build());
    s.add(new S("stale aggregate with retry(3) succeeds on attempt 2").prior(T1).uc(retry(uc("edit"), 3)).fault("concurrentWrite", null, 1).build());
    s.add(new S("stale aggregate with retry(3) and a load phase re-runs load and authorizeLoaded").prior(T1).uc(retry(loaded("ok"), 3)).fault("concurrentWrite", null, 1).build());
    s.add(new S("stale aggregate on every attempt exhausts the attempts").prior(T1).uc(retry(uc("edit"), 3)).fault("concurrentWrite", null, 1, 2, 3).build());
    s.add(new S("stale aggregate with retry(2) exhausted after two attempts").prior(T1).uc(retry(uc("edit"), 2)).fault("concurrentWrite", null, 1, 2).build());
    s.add(new S("SQLSTATE 40001 with fail").prior(T1).fault("sqlState", "40001", 1).build());
    s.add(new S("SQLSTATE 40001 retried").prior(T1).uc(retry(uc("edit"), 3)).fault("sqlState", "40001", 1).build());
    s.add(new S("SQLSTATE 40P01 retried").prior(T1).uc(retry(uc("edit"), 3)).fault("sqlState", "40P01", 1, 2).build());
    s.add(new S("SQLSTATE 40001 exhausted").prior(T1).uc(retry(uc("edit"), 2)).fault("sqlState", "40001", 1, 2).build());
    s.add(new S("SQLSTATE 23505 is a unique violation and is not retried").prior(T1).uc(retry(uc("edit"), 3)).fault("sqlState", "23505", 1).build());
    s.add(new S("a real unique violation names the constraint and is not retried").prior(T1, "t2|acme|1|nut|N-1").uc(retry(uc("edit"), 3)).cmd(new Cmd("edit", "t2", null, "B-1")).build());
    s.add(new S("a unique violation on insert").prior(T1).uc(uc("create")).cmd(new Cmd("create", "t9", "dup", "B-1")).build());
    s.add(new S("a stale delete with fail").prior(T1).uc(uc("remove")).cmd(new Cmd("remove", "t1", null, null)).fault("concurrentWrite", null, 1).build());
    // 5 step codes
    s.add(new S("persist failure").prior(T1).fault("persistError", "XX000", 1).build());
    s.add(new S("events (outbox) failure rolls everything back").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).fault("events", null, 1).build());
    s.add(new S("audit failure rolls everything back").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).fault("audit", null, 1).build());
    s.add(new S("commit failure rolls everything back").uc(uc("create")).cmd(new Cmd("create", "t1", "commit-fails", "B-1")).fault("commitTrigger", null, 1).build());
    s.add(new S("begin failure: the database cannot give a connection").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).fault("begin", null, 1).build());
    s.add(new S("a failure after the first write rolls the first write back").prior(T1).uc(uc("createThenClash")).cmd(new Cmd("create", "-", "x", "B-1")).build());
    // 6 cross client
    s.add(new S("a plan holding another client's aggregate").prior(T1).uc(uc("crossClient")).build());
    s.add(new S("a plan deleting another client's aggregate").prior("t1|other|1|bolt|B-1").uc(uc("crossClientDelete")).cmd(new Cmd("remove", "t1", null, null)).build());
    s.add(new S("a cross client plan is not retried").prior(T1).uc(retry(uc("crossClient"), 3)).build());
    // 7 interrupt
    s.add(new S("interrupted while waiting between attempts").prior(T1).uc(retry(uc("edit"), 3)).fault("concurrentWrite", null, 1).fault("interrupt", null, 1).build());
    // 8 join
    s.add(new S("join: commits in the caller's transaction, visible after the caller commits").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).join(new Join(true, "commit", false, null)).build());
    s.add(new S("join: gone when the caller rolls back").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).join(new Join(true, "rollback", false, null)).build());
    s.add(new S("join: a unique violation rolls back to the savepoint and the transaction is usable").prior("t0|acme|1|first|DUP").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "DUP")).join(new Join(true, "commit", false, new Cmd("create", "t2", "washer", "W-1"))).build());
    s.add(new S("join: stale aggregate with fail").prior(T1).fault("concurrentWrite", null, 1).join(new Join(true, "commit", false, null)).build());
    s.add(new S("join: stale aggregate retried on the same transaction").prior(T1).uc(retry(uc("edit"), 2)).fault("concurrentWrite", null, 1).join(new Join(true, "commit", false, null)).build());
    s.add(new S("join: SQLSTATE 40001 retried on the same transaction").prior(T1).uc(retry(uc("edit"), 3)).fault("sqlState", "40001", 1).join(new Join(true, "commit", false, null)).build());
    s.add(new S("join: events failure keeps the caller's earlier work").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).fault("events", null, 1).join(new Join(true, "commit", false, new Cmd("create", "t2", "washer", "W-1"))).build());
    s.add(new S("join: a rejected run writes nothing and keeps the transaction").join(new Join(true, "commit", false, null)).build());
    s.add(new S("join: a connection in auto-commit is an argument error").uc(uc("create")).cmd(new Cmd("create", "t1", "bolt", "B-1")).join(new Join(false, "commit", true, null)).build());
    s.add(new S("join: a cross client plan fails and the caller's work survives").prior(T1).uc(uc("crossClient")).join(new Join(true, "commit", false, null)).build());
    return s;
  }

  // ---- running one -------------------------------------------------------------------------

  @Test
  void theVectorMatchesWhatTheJavaRunnerDoes() throws Exception {
    ObjectNode doc = JsonVectors.mapper().createObjectNode();
    doc.put(
        "description",
        "Use case runner scenarios (write-path contract sections 3a, 4, 4a, 5). Setup: aggregate table thing(id text"
            + " primary key, client_id text, version bigint, name text, code text UNIQUE); rows are written as"
            + " 'id|client|version|name|code' ('-' = null code). Command: op (create | edit | remove | emit), id, name, code;"
            + " create inserts a new thing (version 0 -> 1), edit changes name and/or code of the thing found, remove"
            + " deletes it, emit plans an event only. useCase: validate/authorize = ok | refuse (an error of kind"
            + " VALIDATION / AUTHORIZATION) | throw (an exception whose message must never reach the error);"
            + " load = none | ok (finds the thing by id, scoped to the context's client) | throw; whenMissing is"
            + " NOT_FOUND THING_NOT_FOUND; authorizeLoaded = none | ok (refuses a thing named 'locked' with AUTHORIZATION"
            + " LOCKED) | refuse (always AUTHORIZATION LOCKED) | hide (answers whenMissing) | throw; execute = edit | create |"
            + " remove | emit | reject (BUSINESS_RULE REJECTED) | throw | crossClient / crossClientDelete (a plan holding a"
            + " thing of client 'other') | createThenClash (saves two things, the second violating the unique code);"
            + " without a load phase edit/remove find the thing themselves (trace 'find'). conflict = fail | retry"
            + " (maxAttempts, backoff 5 ms, jittered 0.5x to 1.5x). faults: concurrentWrite (another writer bumps the row's"
            + " version after the attempt loaded it, so the update affects 0 rows) | sqlState (the update throws an"
            + " SQLException with that SQLSTATE) | persistError (any other exception in the persist step) | events | audit"
            + " (the sink throws) | commitTrigger (a deferred constraint fails at COMMIT) | begin (the database cannot give"
            + " a connection) | interrupt (the wait between attempts is interrupted); attempts = the 1-based attempts the"
            + " fault applies to. join: null runs in the runner's own transaction; else the runner is handed the caller's"
            + " connection (autoCommit false unless stated), callerInsert writes the row mine|acme|1|callers|C-1 in it"
            + " first, end = commit | rollback, followUp = a further command run on the same transaction after the first."
            + " expect: result committed | failed; error {kind, code, httpStatus, details} (message is Java's wording in"
            + " messageJava and is not compared; a cause's text never appears in it); trace = the phases called, in order,"
            + " across all attempts; attempts = how many attempts ran; waits = how many waits between attempts;"
            + " events = [{type, subject}] the sink received (committed work only); audits = [{entityType, entityId,"
            + " operation}]; things = the aggregate rows after the scenario ('id|client|version|name|code'); for a join:"
            + " seenByOthersBeforeEnd = rows another connection sees while the caller's transaction is open, and"
            + " followUp the result of the second run; throws = the exception class name when the run is an argument error."
            + " Outbox rows proper are covered by outbox/outbox.json; here the sink's events stand for them.");
    doc.put(
        "howToRun",
        "Per scenario: reset the database, create the table, insert prior, build the use case from 'useCase', inject"
            + " the faults, run it with the context (client, principal usr_1) and the command (joined or not), then compare"
            + " the expect block. Regenerate from Java with -Dconformance.update=true (UseCaseScenariosConformanceTest).");
    ArrayNode out = doc.putArray("scenarios");
    for (Scenario sc : scenarios()) {
      ObjectNode item = out.addObject();
      item.put("name", sc.name());
      ArrayNode prior = item.putArray("prior");
      sc.prior().forEach(prior::add);
      ObjectNode u = item.putObject("useCase");
      u.put("validate", sc.uc().validate());
      u.put("authorize", sc.uc().authorize());
      u.put("load", sc.uc().load());
      u.put("authorizeLoaded", sc.uc().authorizeLoaded());
      u.put("execute", sc.uc().execute());
      if (sc.uc().conflict().equals("retry")) {
        ObjectNode cf = u.putObject("conflict");
        cf.put("policy", "retry");
        cf.put("maxAttempts", sc.uc().maxAttempts());
        cf.put("backoffMs", 5);
      } else {
        u.putObject("conflict").put("policy", "fail");
      }
      item.set("command", cmdNode(sc.command()));
      item.put("client", sc.client());
      ArrayNode faults = item.putArray("faults");
      for (Fault f : sc.faults()) {
        ObjectNode fn = faults.addObject();
        fn.put("kind", f.kind());
        if (f.state() != null) {
          fn.put("state", f.state());
        }
        ArrayNode at = fn.putArray("attempts");
        f.attempts().forEach(at::add);
      }
      if (sc.join() != null) {
        ObjectNode j = item.putObject("join");
        j.put("callerInsert", sc.join().callerInsert());
        j.put("end", sc.join().end());
        j.put("autoCommit", sc.join().autoCommit());
        if (sc.join().followUp() != null) {
          j.set("followUp", cmdNode(sc.join().followUp()));
        }
      }
      item.set("expect", run(sc));
    }
    JsonVectors.check(FILE, doc);
  }

  private static ObjectNode cmdNode(Cmd c) {
    ObjectNode n = JsonVectors.mapper().createObjectNode();
    n.put("op", c.op());
    n.put("id", c.id());
    if (c.name() != null) {
      n.put("name", c.name());
    }
    if (c.code() != null) {
      n.put("code", c.code());
    }
    return n;
  }

  /** One scenario's world. */
  final class World {
    App app;
    Database db;
    final RecordingSink sink = new RecordingSink();
    final ThingRepository repo = new ThingRepository();
    final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger executes = new AtomicInteger();
    final AtomicInteger waits = new AtomicInteger();
    final InstantSource clock = () -> NOW;
    Events events;
    UseCases useCases;
    Scenario sc;

    boolean faultAt(String kind, int attempt) {
      return sc.faults().stream().anyMatch(f -> f.kind().equals(kind) && f.attempts().contains(attempt));
    }

    Fault fault(String kind, int attempt) {
      return sc.faults().stream().filter(f -> f.kind().equals(kind) && f.attempts().contains(attempt)).findFirst().orElse(null);
    }
  }

  private ObjectNode run(Scenario sc) throws Exception {
    World w = new World();
    w.sc = sc;
    pg.reset();
    Path cfg = Files.createTempDirectory(dir, "app");
    Files.writeString(
        cfg.resolve("application.yaml"),
        """
        database:
          instances:
            main: { url: %s, username: %s, password: %s }
        """
            .formatted(pg.jdbcUrl(), pg.username(), pg.password()));
    w.app = App.builder().configDirectory(cfg).environment(Map.of()).instantSource(w.clock).module(new DatabaseModule()).start();
    try {
      w.db = w.app.get(Databases.class).defaultInstance();
      pg.execute(ThingRepository.DDL);
      if (sc.faults().stream().anyMatch(f -> f.kind().equals("commitTrigger"))) {
        pg.execute(COMMIT_TRIGGER);
      }
      for (String row : sc.prior()) {
        String[] p = row.split("\\|", -1);
        pg.execute(
            "INSERT INTO thing (id, client_id, version, name, code) VALUES ('" + p[0] + "', '" + p[1] + "', " + p[2] + ", '" + p[3] + "', "
                + (p[4].equals("-") ? "NULL" : "'" + p[4] + "'") + ")");
      }
      w.events = new Events(w.clock, w.app.get(Ids.class));
      boolean interrupts = sc.faults().stream().anyMatch(f -> f.kind().equals("interrupt"));
      w.useCases =
          new UseCases(
              w.db,
              w.sink,
              w.clock,
              d -> {
                w.waits.incrementAndGet();
                assertThat(d.toNanos()).isBetween(Duration.ofMillis(5).toNanos() / 2, Duration.ofMillis(5).toNanos() * 3 / 2 + 1);
                if (interrupts) {
                  throw new InterruptedException();
                }
              });
      installFaults(w);
      if (sc.faults().stream().anyMatch(f -> f.kind().equals("events"))) {
        w.sink.failEvents = new IllegalStateException("disk full at /var/secret");
      }
      if (sc.faults().stream().anyMatch(f -> f.kind().equals("audit"))) {
        w.sink.failAudit = new IllegalStateException("nope at /var/secret");
      }
      if (sc.faults().stream().anyMatch(f -> f.kind().equals("begin"))) {
        w.db.close();
      }
      ObjectNode expect = JsonVectors.mapper().createObjectNode();
      if (sc.join() == null) {
        UseCaseResult<ThingEvent> result = w.useCases.run(build(w, sc.uc()), ctx(sc.client()), sc.command());
        result(expect, result);
      } else {
        runJoined(w, sc, expect);
      }
      if (interrupts) {
        assertThat(Thread.interrupted()).isTrue();
      }
      if (sc.join() == null) {
        summarize(w, expect);
      }
      ArrayNode things = expect.putArray("things");
      for (String row : thingRows()) {
        things.add(row);
      }
      return expect;
    } finally {
      w.app.close();
    }
  }

  private void summarize(World w, ObjectNode expect) {
    expect.put("attempts", w.trace.stream().filter(t -> t.equals("validate")).count());
    expect.put("waits", w.waits.get());
    ArrayNode trace = expect.putArray("trace");
    new ArrayList<>(w.trace).forEach(trace::add);
    ArrayNode events = expect.putArray("events");
    for (DomainEvent e : w.sink.events) {
      events.addObject().put("type", e.metadata().type()).put("subject", e.metadata().subject());
    }
    ArrayNode audits = expect.putArray("audits");
    for (AuditEntry a : w.sink.audits) {
      audits.addObject().put("entityType", a.entityType()).put("entityId", a.entityId()).put("operation", a.operation());
    }
  }

  private List<String> thingRows() {
    String joined =
        pg.query(
            "SELECT coalesce(string_agg(id || '|' || client_id || '|' || version || '|' || name || '|' || coalesce(code, '-'), ';' ORDER BY id), '') FROM thing");
    return joined.isEmpty() ? List.of() : List.of(joined.split(";"));
  }

  private static Context ctx(String client) {
    return Context.of(new ClientId(client)).withPrincipal(new PrincipalId("usr_1"));
  }

  private void result(ObjectNode expect, UseCaseResult<ThingEvent> result) {
    switch (result) {
      case UseCaseResult.Committed<ThingEvent> c -> expect.put("result", "committed");
      case UseCaseResult.Failed<ThingEvent> f -> {
        expect.put("result", "failed");
        UseCaseError e = f.error();
        ObjectNode err = expect.putObject("error");
        err.put("kind", e.kind().name());
        err.put("code", e.code());
        err.put("httpStatus", e.httpStatus());
        ObjectNode details = err.putObject("details");
        e.details().forEach(details::put);
        expect.put("messageJava", e.message());
        assertThat(e.message()).doesNotContain("hunter2").doesNotContain("secret").doesNotContain("10.0.0.5");
      }
    }
  }

  private void runJoined(World w, Scenario sc, ObjectNode expect) throws Exception {
    Join j = sc.join();
    try (Connection c = w.db.dataSource().getConnection()) {
      c.setAutoCommit(j.autoCommit());
      if (!j.autoCommit() && j.callerInsert()) {
        try (var st = c.createStatement()) {
          st.executeUpdate("INSERT INTO thing (id, client_id, version, name, code) VALUES ('mine', 'acme', 1, 'callers', 'C-1')");
        }
      }
      UseCaseResult<ThingEvent> result;
      try {
        result = w.useCases.run(build(w, sc.uc()), ctx(sc.client()), sc.command(), Tx.of(c));
      } catch (IllegalArgumentException e) {
        expect.put("throws", "IllegalArgumentException");
        return;
      }
      result(expect, result);
      summarize(w, expect);
      if (j.followUp() != null) {
        w.sink.failEvents = null;
        w.sink.failAudit = null;
        w.executes.set(0);
        UseCaseResult<ThingEvent> follow = w.useCases.run(build(w, with(uc("create"), "execute", "create")), ctx(sc.client()), j.followUp(), Tx.of(c));
        ObjectNode f = expect.putObject("followUp");
        f.put("result", follow instanceof UseCaseResult.Committed<?> ? "committed" : "failed");
        f.put("events", w.sink.events.size());
      }
      ArrayNode seen = expect.putArray("seenByOthersBeforeEnd");
      thingRows().forEach(seen::add);
      if (j.end().equals("commit")) {
        c.commit();
      } else {
        c.rollback();
      }
    }
  }

  // ---- faults ------------------------------------------------------------------------------

  private void installFaults(World w) {
    w.repo.beforeUpdate =
        thing -> {
          int attempt = w.executes.get();
          Fault sql = w.fault("sqlState", attempt);
          if (sql != null) {
            throw new SQLException("password=hunter2 at 10.0.0.5", sql.state());
          }
          Fault persist = w.fault("persistError", attempt);
          if (persist != null) {
            throw new SQLException("password=hunter2 at 10.0.0.5", persist.state());
          }
        };
  }

  // ---- the use case built from its description ---------------------------------------------

  private UseCase<Cmd, ThingEvent> build(World w, Uc u) {
    UseCase.Builder<Cmd, ThingEvent> b = UseCase.named("scenario", Cmd.class, ThingEvent.class);
    b.validate(
        (c, cmd) -> {
          w.trace.add("validate");
          return switch (u.validate()) {
            case "refuse" -> Optional.of(UseCaseError.validation("NAME_REQUIRED", "name?"));
            case "throw" -> throw new IllegalStateException("password=hunter2");
            default -> Optional.empty();
          };
        });
    b.authorize(
        (c, cmd) -> {
          w.trace.add("authorize");
          return switch (u.authorize()) {
            case "refuse" -> Optional.of(UseCaseError.authorization("FORBIDDEN_OP", "no"));
            case "throw" -> throw new IllegalStateException("password=hunter2");
            default -> Optional.empty();
          };
        });
    if (u.conflict().equals("retry")) {
      b.conflict(ConflictPolicy.retry(u.maxAttempts(), Duration.ofMillis(5)));
    }
    if (u.load().equals("none")) {
      return b.execute((c, cmd) -> execute(w, u, c, cmd, null));
    }
    UseCase.LoadedBuilder<Cmd, Thing, ThingEvent> lb =
        b.load(
            (c, cmd) -> {
              w.trace.add("load");
              if (u.load().equals("throw")) {
                throw new SQLException("password=hunter2 at 10.0.0.5", "XX000");
              }
              return w.repo.find(w.db, c.client(), new ThingId(cmd.id()));
            },
            UseCaseError.notFound("THING_NOT_FOUND", "no such thing"));
    if (!u.authorizeLoaded().equals("none")) {
      lb.authorizeLoaded(
          (c, cmd, thing) -> {
            w.trace.add("authorizeLoaded");
            return switch (u.authorizeLoaded()) {
              case "refuse" -> Optional.of(UseCaseError.authorization("LOCKED", "that thing is locked"));
              case "hide" -> Optional.of(UseCaseError.notFound("THING_NOT_FOUND", "no such thing"));
              case "throw" -> throw new IllegalStateException("password=hunter2");
              default ->
                  thing.name().equals("locked")
                      ? Optional.of(UseCaseError.authorization("LOCKED", "that thing is locked"))
                      : Optional.empty();
            };
          });
    }
    return lb.execute((c, cmd, thing) -> execute(w, u, c, cmd, thing));
  }

  private Decision<ThingEvent> execute(World w, Uc u, Context c, Cmd cmd, Thing loaded) throws Exception {
    w.trace.add("execute");
    w.executes.incrementAndGet();
    switch (u.execute()) {
      case "throw" -> throw new SQLException("password=hunter2 at 10.0.0.5", "XX000");
      case "reject" -> {
        return Decision.reject(UseCaseError.businessRule("REJECTED", "not allowed"));
      }
      case "create" -> {
        Thing t = Thing.create(new ThingId(cmd.id()), c.client(), cmd.name(), cmd.code());
        return Plan.save(t, w.repo, event(w, c, CREATED, t));
      }
      case "createThenClash" -> {
        Thing fine = Thing.create(new ThingId("fine"), c.client(), "f", "F");
        Thing clash = Thing.create(new ThingId("clash"), c.client(), "c", "B-1");
        return Plan.save(fine, w.repo, event(w, c, CREATED, fine)).andSave(clash, w.repo, event(w, c, CREATED, clash));
      }
      case "crossClient" -> {
        Thing t = Thing.create(new ThingId("x1"), new ClientId("other"), "x", "X");
        return Plan.save(t, w.repo, event(w, c, CREATED, t));
      }
      case "crossClientDelete" -> {
        Thing t = new Thing(new ThingId(cmd.id()), new ClientId("other"), 1, "bolt", "B-1");
        return Plan.delete(t, w.repo, event(w, c, REMOVED, t));
      }
      default -> {
        Thing found = loaded;
        if (found == null) {
          w.trace.add("find");
          Optional<Thing> f = w.repo.find(w.db, c.client(), new ThingId(cmd.id()));
          if (f.isEmpty()) {
            return Decision.reject(UseCaseError.notFound("THING_NOT_FOUND", "no such thing"));
          }
          found = f.get();
        }
        if (w.faultAt("concurrentWrite", w.executes.get())) {
          // Another writer changes the row after this attempt has loaded it.
          pg.execute("UPDATE thing SET version = version + 1 WHERE id = '" + found.id().value() + "'");
        }
        return switch (u.execute()) {
          case "remove" -> Plan.delete(found, w.repo, event(w, c, REMOVED, found));
          case "emit" -> Plan.emit(event(w, c, CHANGED, found));
          default -> {
            Thing t = found;
            if (cmd.name() != null) {
              t = t.withName(cmd.name());
            }
            if (cmd.code() != null) {
              t = t.withCode(cmd.code());
            }
            yield Plan.save(t, w.repo, event(w, c, CHANGED, t));
          }
        };
      }
    }
  }

  private static ThingEvent event(World w, Context c, EventType type, Thing t) {
    return new ThingEvent(w.events.metadata(c, type, t.id()), t.id().value(), t.name(), t.code());
  }
}
