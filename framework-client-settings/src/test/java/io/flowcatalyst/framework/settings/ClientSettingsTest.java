package io.flowcatalyst.framework.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.DatabaseModule;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.usecase.Authorize;
import io.flowcatalyst.framework.usecase.ConflictPolicy;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.ErrorKind;
import io.flowcatalyst.framework.usecase.EventMetadata;
import io.flowcatalyst.framework.usecase.EventType;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.OutboxModule;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseError;
import io.flowcatalyst.framework.usecase.UseCaseModule;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-client settings against PostgreSQL (client-settings contract, section 8). */
class ClientSettingsTest {

  record Minutes(int low, int urgent) {}

  record Changed(EventMetadata metadata, String client) implements DomainEvent {}

  record Set(Minutes minutes) {}

  static final EventType CHANGED = new EventType("settings-test", "tests", "settings", "changed");

  static final ClientSetting<Minutes> SLA =
      ClientSetting.of("tickets.sla", Minutes.class, new Minutes(2880, 60))
          .rule(m -> m.urgent() > 0 && m.urgent() <= m.low() ? Optional.empty() : Optional.of("urgent must be positive and not longer than low"));
  static final ClientSetting<String> PREFIX = ClientSetting.of("invoice.prefix", String.class, "INV-");

  static PostgresFixture pg;

  @TempDir Path dir;

  final ClientId acme = new ClientId("acme");
  final ClientId globex = new ClientId("globex");
  App app;
  Database db;
  ClientSettingsRepository repo;
  ClientSettingsStore store;
  UseCases useCases;

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
            .module(new DatabaseModule())
            .module(new OutboxModule())
            .module(new UseCaseModule())
            .module(new ClientSettingsModule())
            .start();
    db = app.get(Databases.class).defaultInstance();
    repo = app.get(ClientSettingsRepository.class);
    store = app.get(ClientSettingsStore.class);
    useCases = app.get(UseCases.class);
  }

  @AfterEach
  void stop() {
    app.close();
  }

  private Context ctx(ClientId client) {
    return Context.of(client).withPrincipal(new PrincipalId("usr_1"));
  }

  /** A use case that sets the SLA, as an application would write it. */
  private UseCase<Set, Changed> setSla(ConflictPolicy policy) {
    Events events = app.get(Events.class);
    return UseCase.named("set-sla", Set.class, Changed.class)
        .validate(
            (c, cmd) -> SLA.check(cmd.minutes()).map(message -> UseCaseError.validation("SLA_INVALID", message)))
        .authorize(Authorize.PUBLIC)
        .conflict(policy)
        .execute(
            (c, cmd) -> {
              ClientSettings changed = repo.forClient(db, c.client()).with(SLA, cmd.minutes());
              return Plan.save(changed, repo, new Changed(events.metadata(c, CHANGED, changed.id()), c.client().value()));
            });
  }

  private UseCaseResult<Changed> run(ClientId client, Minutes minutes) {
    return useCases.run(setSla(ConflictPolicy.fail()), ctx(client), new Set(minutes));
  }

  @Test
  void aClientWithNoRowGetsTheDefault() throws Exception {
    assertThat(store.get(acme, SLA)).isEqualTo(new Minutes(2880, 60));
    assertThat(store.get(acme, PREFIX)).isEqualTo("INV-");
    assertThat(repo.forClient(db, acme).version()).isZero();
    assertThat(repo.forClient(db, acme).isSet(SLA)).isFalse();
  }

  @Test
  void aWrittenValueIsReadBackTypedByTheStoreAndTheAggregateAndOtherClientsAreUnaffected() throws Exception {
    assertThat(run(acme, new Minutes(100, 10))).isInstanceOf(UseCaseResult.Committed.class);

    assertThat(store.get(acme, SLA)).isEqualTo(new Minutes(100, 10));
    assertThat(repo.forClient(db, acme).get(SLA)).isEqualTo(new Minutes(100, 10));
    assertThat(repo.forClient(db, acme).version()).isEqualTo(1);
    assertThat(store.get(globex, SLA)).as("another client keeps the default").isEqualTo(new Minutes(2880, 60));
    assertThat(store.get(acme, PREFIX)).as("another setting of the same client").isEqualTo("INV-");
  }

  @Test
  void aSecondWriteUpdatesTheSameRowAndSeveralSettingsLiveInIt() throws Exception {
    committed(run(acme, new Minutes(100, 10)));
    ClientSettings both = repo.forClient(db, acme).with(PREFIX, "ACME-");
    committed(useCases.run(plain(both), ctx(acme), new Set(new Minutes(100, 10))));

    assertThat(store.get(acme, PREFIX)).isEqualTo("ACME-");
    assertThat(store.get(acme, SLA)).isEqualTo(new Minutes(100, 10));
    assertThat(pg.query("SELECT count(*) FROM fw_client_settings")).isEqualTo("1");
    assertThat(pg.query("SELECT version FROM fw_client_settings WHERE client_id = 'acme'")).isEqualTo("2");
    // The stored shape is an object from key to the value's JSON.
    assertThat(pg.query("SELECT settings::text FROM fw_client_settings WHERE client_id = 'acme'"))
        .contains("\"invoice.prefix\": \"ACME-\"")
        .contains("\"tickets.sla\": {\"low\": 100, \"urgent\": 10}");
  }

  private UseCase<Set, Changed> plain(ClientSettings toSave) {
    Events events = app.get(Events.class);
    return UseCase.named("save-settings", Set.class, Changed.class)
        .authorize(Authorize.PUBLIC)
        .execute((c, cmd) -> Plan.save(toSave, repo, new Changed(events.metadata(c, CHANGED, toSave.id()), c.client().value())));
  }

  @Test
  void theRuleRefusesABadValueAsAValidationErrorAndWithThrowsAsTheBackstop() throws Exception {
    UseCaseResult<Changed> result = run(acme, new Minutes(10, 100));

    assertThat(result).isInstanceOf(UseCaseResult.Failed.class);
    UseCaseError error = ((UseCaseResult.Failed<Changed>) result).error();
    assertThat(error.kind()).isEqualTo(ErrorKind.VALIDATION);
    assertThat(error.code()).isEqualTo("SLA_INVALID");
    assertThat(pg.query("SELECT count(*) FROM fw_client_settings")).isEqualTo("0");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientSettings.empty(acme).with(SLA, new Minutes(10, 100)))
        .withMessageContaining("tickets.sla");
    assertThat(SLA.check(new Minutes(10, 100))).isPresent();
    assertThat(SLA.check(new Minutes(100, 10))).isEmpty();
  }

  @Test
  void withoutRestoresTheDefault() throws Exception {
    committed(run(acme, new Minutes(100, 10)));
    ClientSettings cleared = repo.forClient(db, acme).without(SLA);
    committed(useCases.run(plain(cleared), ctx(acme), new Set(new Minutes(100, 10))));

    assertThat(store.get(acme, SLA)).isEqualTo(new Minutes(2880, 60));
    assertThat(repo.forClient(db, acme).isSet(SLA)).isFalse();
  }

  @Test
  void aStoredValueThatNoLongerDecodesIsAnErrorNamingTheKeyNotTheDefault() throws Exception {
    committed(run(acme, new Minutes(100, 10)));
    pg.execute("UPDATE fw_client_settings SET settings = '{\"tickets.sla\": \"seven\"}'::jsonb WHERE client_id = 'acme'");

    assertThatIllegalStateException().isThrownBy(() -> store.get(acme, SLA)).withMessageContaining("tickets.sla").withMessageContaining("acme");
  }

  @Test
  void aSettingValueGainingFieldsStillReadsOldRows() throws Exception {
    pg.execute("INSERT INTO fw_client_settings (client_id, version, settings) VALUES ('acme', 1, '{\"tickets.sla\": {\"low\": 5, \"urgent\": 1, \"extra\": true}}')");

    assertThat(store.get(acme, SLA)).isEqualTo(new Minutes(5, 1));
  }

  @Test
  void keysAreValidatedAndTheDefaultMustSatisfyItsRule() {
    for (String bad : new String[] {"", "Tickets", "1a", "a b", "a:b", "x".repeat(101)}) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> ClientSetting.of(bad, String.class, "x"));
    }
    ClientSetting.of("a.b-c_d", String.class, "x");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientSetting.of("n", Integer.class, 5).rule(n -> n > 10 ? Optional.empty() : Optional.of("too small")))
        .withMessageContaining("default");
  }

  @Test
  void twoWritersOfOneRowConflictAndTheLoserSeesAStaleAggregate() throws Exception {
    committed(run(acme, new Minutes(100, 10)));
    ClientSettings first = repo.forClient(db, acme);
    ClientSettings second = repo.forClient(db, acme);

    committed(useCases.run(plain(first.with(PREFIX, "ONE-")), ctx(acme), new Set(new Minutes(100, 10))));
    UseCaseResult<Changed> loser = useCases.run(plain(second.with(PREFIX, "TWO-")), ctx(acme), new Set(new Minutes(100, 10)));

    assertThat(loser).isInstanceOf(UseCaseResult.Failed.class);
    assertThat(((UseCaseResult.Failed<Changed>) loser).error().code()).isEqualTo("STALE_AGGREGATE");
    assertThat(store.get(acme, PREFIX)).isEqualTo("ONE-");
  }

  @Test
  void twoFirstWritesAtOnceTheSecondIsAUniqueViolation() throws Exception {
    ClientSettings a = ClientSettings.empty(acme).with(PREFIX, "A-");
    ClientSettings b = ClientSettings.empty(acme).with(PREFIX, "B-");

    committed(useCases.run(plain(a), ctx(acme), new Set(new Minutes(100, 10))));
    UseCaseResult<Changed> loser = useCases.run(plain(b), ctx(acme), new Set(new Minutes(100, 10)));

    assertThat(((UseCaseResult.Failed<Changed>) loser).error().code()).isEqualTo("UNIQUE_VIOLATION");
    assertThat(store.get(acme, PREFIX)).isEqualTo("A-");
  }

  @Test
  void aSettingsAggregateOfAnotherClientIsRefusedByTheRunner() throws Exception {
    UseCaseResult<Changed> result = useCases.run(plain(ClientSettings.empty(globex).with(PREFIX, "G-")), ctx(acme), new Set(new Minutes(100, 10)));

    assertThat(((UseCaseResult.Failed<Changed>) result).error().code()).isEqualTo("CROSS_CLIENT_WRITE");
    assertThat(pg.query("SELECT count(*) FROM fw_client_settings")).isEqualTo("0");
  }

  @Test
  void theSettingsTheEventAndTheAuditRowCommitTogether() throws Exception {
    committed(run(acme, new Minutes(100, 10)));

    assertThat(pg.query("SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'")).isEqualTo("1");
    assertThat(pg.query("SELECT count(*) FROM outbox_messages WHERE type = 'AUDIT_LOG'")).isEqualTo("1");
  }

  @Test
  void aRolledBackRunWritesNoSettings() throws Exception {
    pg.execute("ALTER TABLE outbox_messages ADD CONSTRAINT no_events CHECK (type <> 'EVENT')");

    UseCaseResult<Changed> result = run(acme, new Minutes(100, 10));

    assertThat(((UseCaseResult.Failed<Changed>) result).error().kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(pg.query("SELECT count(*) FROM fw_client_settings")).isEqualTo("0");
  }

  @Test
  void theRepositoryFindsOnlyTheCallersOwnClient() throws Exception {
    committed(run(acme, new Minutes(100, 10)));

    assertThat(repo.find(db, globex, ClientSettingsId.of(acme))).isEmpty();
    assertThat(repo.find(db, acme, ClientSettingsId.of(acme))).isPresent();
  }

  private static void committed(UseCaseResult<?> result) {
    assertThat(result).isInstanceOf(UseCaseResult.Committed.class);
  }
}
