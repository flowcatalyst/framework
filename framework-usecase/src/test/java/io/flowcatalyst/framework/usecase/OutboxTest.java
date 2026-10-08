package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The write path with the real outbox: what lands in {@code outbox_messages}, and rollback. */
class OutboxTest {

  static final EventType CREATED = new EventType("things", "stock", "thing", "created");
  static final Instant NOW = Instant.parse("2026-10-08T10:15:30.123Z");

  record Create(String id, String code) implements AuditData {
    @Override
    public Map<String, Object> auditData() {
      return Map.of("code", code);
    }
  }

  static PostgresFixture pg;

  @TempDir Path dir;

  final ClientId acme = new ClientId("acme");
  final InstantSource clock = () -> NOW;
  final ThingRepository repo = new ThingRepository();
  final ObjectMapper json = Json.newMapper();

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
            .module(new OutboxModule())
            .module(new UseCaseModule())
            .start();
    db = app.get(Databases.class).defaultInstance();
    pg.execute(ThingRepository.DDL);
    useCases = app.get(UseCases.class);
    events = app.get(Events.class);
  }

  @AfterEach
  void stop() {
    app.close();
  }

  Context ctx() {
    return Context.of(acme)
        .withPrincipal(new PrincipalId("usr_1"))
        .withApplication(new ApplicationCode("things"))
        .withExecutionId("job_7");
  }

  UseCase<Create, ThingEvent> create() {
    return UseCase.named("create-thing", Create.class, ThingEvent.class)
        .authorize(Authorize.PUBLIC)
        .execute(
            (c, cmd) -> {
              Thing t = Thing.create(new ThingId(cmd.id()), c.client(), "bolt", cmd.code());
              return Plan.save(
                  t,
                  repo,
                  new ThingEvent(events.metadata(c, CREATED, t.id()), cmd.id(), t.name(), t.code()));
            });
  }

  List<Map<String, String>> rows(String sql) {
    try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.username(), pg.password());
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      ResultSetMetaData meta = rs.getMetaData();
      List<Map<String, String>> out = new ArrayList<>();
      while (rs.next()) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnName(i), rs.getString(i));
        }
        out.add(row);
      }
      return out;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  int count(String table) {
    return Integer.parseInt(pg.query("SELECT count(*) FROM " + table));
  }

  // ---- the migration -----------------------------------------------------------------------

  @Test
  void theOutboxMigrationIsContributedUnderTheOutboxModuleId() {
    assertThat(
            rows("SELECT module, version, description FROM fw_schema_history WHERE module = 'outbox'"))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.get("version")).isEqualTo("1");
              assertThat(r.get("description")).contains("outbox");
            });
    assertThat(count("outbox_messages")).isZero();
  }

  @Test
  void theRecordedChecksumIsThatOfTheSdkMigrationFile() throws Exception {
    String recorded = pg.query("SELECT checksum FROM fw_schema_history WHERE module = 'outbox'");
    try (var in = getClass().getResourceAsStream("/db/migration/outbox/V0001__create_outbox_messages.sql")) {
      assertThat(in).isNotNull();
      byte[] ours = in.readAllBytes();
      assertThat(recorded).isEqualTo(sha256(ours));

      Path sdkFile = SdkFiles.outboxMigration();
      org.junit.jupiter.api.Assumptions.assumeTrue(
          Files.exists(sdkFile), "FlowCatalyst Java SDK not found at " + sdkFile);
      assertThat(sha256(Files.readAllBytes(sdkFile))).isEqualTo(recorded);
      assertThat(ours).isEqualTo(Files.readAllBytes(sdkFile));
    }
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  // ---- what a commit writes ----------------------------------------------------------------

  @Test
  void aCommitWritesOneEventRowAndOneAuditRowInTheSameTransaction() throws Exception {
    ThingEvent result =
        ((UseCaseResult.Committed<ThingEvent>) useCases.run(create(), ctx(), new Create("t1", "B-1")))
            .event();

    assertThat(pg.query("SELECT version FROM thing WHERE id = 't1'")).isEqualTo("1");
    List<Map<String, String>> out = rows("SELECT * FROM outbox_messages ORDER BY type");
    assertThat(out).hasSize(2);

    Map<String, String> audit = out.get(0);
    assertThat(audit.get("type")).isEqualTo("AUDIT_LOG");
    assertThat(audit.get("message_group")).isNull();

    Map<String, String> event = out.get(1);
    assertThat(event.get("type")).isEqualTo("EVENT");
    assertThat(event.get("id")).isEqualTo(result.metadata().eventId());
    assertThat(event.get("message_group")).isEqualTo("stock:thing:t1");

    for (Map<String, String> row : out) {
      assertThat(row.get("status")).isEqualTo("0");
      assertThat(row.get("retry_count")).isEqualTo("0");
      assertThat(row.get("client_id")).isEqualTo("acme");
      assertThat(row.get("headers")).isNull();
      assertThat(row.get("error_message")).isNull();
      assertThat(Integer.parseInt(row.get("payload_size")))
          .isEqualTo(row.get("payload").getBytes(StandardCharsets.UTF_8).length);
    }
    assertThat(
            pg.query(
                "SELECT count(*) FROM outbox_messages WHERE created_at = updated_at AND created_at = timestamptz '"
                    + NOW
                    + "'"))
        .isEqualTo("2");
  }

  @Test
  void theEventPayloadIsTheSdkShapeWithTheRecordsFieldsAsADataString() throws Exception {
    ThingEvent result =
        ((UseCaseResult.Committed<ThingEvent>) useCases.run(create(), ctx(), new Create("t1", "B-1")))
            .event();
    JsonNode payload =
        json.readTree(rows("SELECT payload FROM outbox_messages WHERE type = 'EVENT'").get(0).get("payload"));

    assertThat(payload.get("specVersion").asString()).isEqualTo("1.0");
    assertThat(payload.get("type").asString()).isEqualTo("things:stock:thing:created");
    assertThat(payload.get("source").asString()).isEqualTo("things:stock");
    assertThat(payload.get("subject").asString()).isEqualTo("stock.thing.t1");
    assertThat(payload.get("messageGroup").asString()).isEqualTo("stock:thing:t1");
    assertThat(payload.get("clientCode").asString()).isEqualTo("acme");
    assertThat(payload.get("correlationId").asString()).isEqualTo(result.metadata().correlationId());
    assertThat(payload.get("deduplicationId").asString()).isEqualTo(result.metadata().eventId());
    assertThat(payload.has("causationId")).isFalse();
    assertThat(payload.get("data").isString()).isTrue();
    JsonNode data = json.readTree(payload.get("data").asString());
    assertThat(data.propertyNames()).containsExactlyInAnyOrder("thingId", "name", "code");
    assertThat(data.get("thingId").asString()).isEqualTo("t1");
    assertThat(payload.get("contextData").size()).isEqualTo(2);
    assertThat(payload.get("contextData").get(0).get("key").asString()).isEqualTo("principalId");
    assertThat(payload.get("contextData").get(0).get("value").asString()).isEqualTo("usr_1");
    assertThat(payload.get("contextData").get(1).get("key").asString()).isEqualTo("executionId");
  }

  @Test
  void theAuditPayloadIsTheSdkShape() throws Exception {
    useCases.run(create(), ctx(), new Create("t1", "B-1"));
    JsonNode payload =
        json.readTree(rows("SELECT payload FROM outbox_messages WHERE type = 'AUDIT_LOG'").get(0).get("payload"));

    assertThat(payload.get("entityType").asString()).isEqualTo("Thing");
    assertThat(payload.get("entityId").asString()).isEqualTo("t1");
    assertThat(payload.get("operation").asString()).isEqualTo("create-thing");
    assertThat(json.readTree(payload.get("operationData").asString()).get("code").asString())
        .isEqualTo("B-1");
    assertThat(payload.get("principalId").asString()).isEqualTo("usr_1");
    assertThat(payload.get("performedAt").asString()).isEqualTo("2026-10-08T10:15:30.123Z");
    assertThat(payload.get("source").asString()).isEqualTo("things:stock");
    assertThat(payload.get("applicationCode").asString()).isEqualTo("things");
    assertThat(payload.get("clientCode").asString()).isEqualTo("acme");
  }

  @Test
  void eventsAreWrittenInPlanOrderWithTheirOwnMessageGroups() {
    UseCase<Create, ThingEvent> two =
        UseCase.named("create-two", Create.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing a = Thing.create(new ThingId(cmd.id() + "a"), c.client(), "a", "a");
                  Thing b = Thing.create(new ThingId(cmd.id() + "b"), c.client(), "b", "b");
                  return Plan.save(a, repo, event(c, a)).andSave(b, repo, event(c, b));
                });

    useCases.run(two, ctx(), new Create("t", "-"));

    assertThat(rows("SELECT message_group FROM outbox_messages WHERE type = 'EVENT' ORDER BY id"))
        .extracting(r -> r.get("message_group"))
        .containsExactly("stock:thing:ta", "stock:thing:tb");
    assertThat(count("outbox_messages")).isEqualTo(3); // two events and one audit row
  }

  ThingEvent event(Context c, Thing t) {
    return new ThingEvent(events.metadata(c, CREATED, t.id()), t.id().value(), t.name(), t.code());
  }

  // ---- rollback ----------------------------------------------------------------------------

  @Test
  void aUniqueViolationLeavesNoAggregateOutboxOrAuditRow() {
    useCases.run(create(), ctx(), new Create("t1", "B-1"));
    int outboxBefore = count("outbox_messages");

    UseCaseResult<ThingEvent> result = useCases.run(create(), ctx(), new Create("t2", "B-1"));

    assertThat(result).isInstanceOf(UseCaseResult.Failed.class);
    assertThat(count("thing")).isEqualTo(1);
    assertThat(count("outbox_messages")).isEqualTo(outboxBefore);
  }

  /** An event whose JSON cannot be produced, because reading a field throws. */
  record Unwritable(EventMetadata metadata, String thingId) implements DomainEvent {
    @Override
    public String thingId() {
      throw new IllegalStateException("secret-in-cause");
    }
  }

  @Test
  void anEventThatCannotBeSerialisedRollsEverythingBackAndHidesTheCause() {
    UseCase<Create, Unwritable> useCase =
        UseCase.named("create-unwritable", Create.class, Unwritable.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing t = Thing.create(new ThingId(cmd.id()), c.client(), "n", cmd.code());
                  return Plan.save(t, repo, new Unwritable(events.metadata(c, CREATED, t.id()), "x"));
                });

    UseCaseResult<Unwritable> result = useCases.run(useCase, ctx(), new Create("t1", "B-1"));

    UseCaseError error = ((UseCaseResult.Failed<Unwritable>) result).error();
    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("OUTBOX");
    assertThat(error.message()).doesNotContain("secret-in-cause");
    assertThat(count("thing")).isZero();
    assertThat(count("outbox_messages")).isZero();
  }

  @Test
  void anAuditDataThatCannotBeSerialisedRollsEverythingBackIncludingTheEventRow() {
    record Bad(String id) implements AuditData {
      @Override
      public Map<String, Object> auditData() {
        return Map.of("self", new Object() {
          public String getBoom() {
            throw new IllegalStateException("secret-in-cause");
          }
        });
      }
    }
    UseCase<Bad, ThingEvent> useCase =
        UseCase.named("create-bad-audit", Bad.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing t = Thing.create(new ThingId(cmd.id()), c.client(), "n", "c");
                  return Plan.save(t, repo, event(c, t));
                });

    UseCaseResult<ThingEvent> result = useCases.run(useCase, ctx(), new Bad("t1"));

    UseCaseError error = ((UseCaseResult.Failed<ThingEvent>) result).error();
    assertThat(error.code()).isEqualTo("AUDIT");
    assertThat(error.message()).doesNotContain("secret-in-cause");
    assertThat(count("thing")).isZero();
    assertThat(count("outbox_messages")).isZero();
  }

  @Test
  void aStaleUpdateLeavesNoOutboxRowsEither() {
    useCases.run(create(), ctx(), new Create("t1", "B-1"));
    int outboxBefore = count("outbox_messages");
    UseCase<Create, ThingEvent> stale =
        UseCase.named("edit-stale", Create.class, ThingEvent.class)
            .authorize(Authorize.PUBLIC)
            .execute(
                (c, cmd) -> {
                  Thing t = repo.find(db, c.client(), new ThingId(cmd.id())).orElseThrow();
                  pg.execute("UPDATE thing SET version = version + 1");
                  return Plan.save(t.withName("x"), repo, event(c, t));
                });

    UseCaseResult<ThingEvent> result = useCases.run(stale, ctx(), new Create("t1", "-"));

    assertThat(((UseCaseResult.Failed<ThingEvent>) result).error().code())
        .isEqualTo("STALE_AGGREGATE");
    assertThat(count("outbox_messages")).isEqualTo(outboxBefore);
  }

  // ---- module wiring -----------------------------------------------------------------------

  @Test
  void theUseCaseModuleWithoutTheOutboxModuleFailsStartUpNamingWhatIsMissing() {
    Throwable t =
        catchThrowable(
            () ->
                App.builder()
                    .configDirectory(dir)
                    .environment(Map.of())
                    .module(new DatabaseModule())
                    .module(new UseCaseModule())
                    .start());

    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(t.getMessage()).contains("OutboxWriter").contains("usecase");
  }

  @Test
  void theFrameworkProvidesIdsAndEventsToApplications() {
    assertThat(app.get(Ids.class)).isNotNull();
    assertThat(app.get(Events.class)).isNotNull();
  }
}
