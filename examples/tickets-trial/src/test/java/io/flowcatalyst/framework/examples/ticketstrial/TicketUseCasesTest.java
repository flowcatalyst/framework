package io.flowcatalyst.framework.examples.ticketstrial;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.context.PrincipalKind;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Ids;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.AddComment;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.AssignTicket;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.ChangeStatus;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.FlagSlaBreach;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.OpenTicket;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.SetSlaPolicy;
import io.flowcatalyst.framework.examples.ticketstrial.TicketEvents.TicketCommented;
import io.flowcatalyst.framework.usecase.ConflictPolicy;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.ErrorKind;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.UseCaseError;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The write path of the ticket service end to end: real use cases, real PostgreSQL, the config the
 * example ships. The clock is a value the test moves.
 */
class TicketUseCasesTest {

  static final Instant START = Instant.parse("2026-10-17T09:00:00Z");

  static PostgresFixture pg;

  final ClientId acme = new ClientId("acme");
  final ClientId globex = new ClientId("globex");
  final AtomicReference<Instant> now = new AtomicReference<>(START);
  final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

  App app;
  Database db;
  UseCases useCases;
  TicketUseCases tickets;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stopPostgres() {
    pg.close();
  }

  @BeforeEach
  void start() {
    pg.reset();
    now.set(START);
    app =
        TicketsCore.builder()
            .configDirectory(Path.of("config"))
            .environment(
                Map.of(
                    "APP_ENV", "ci",
                    "DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl(),
                    "DATABASE_INSTANCES_MAIN_USERNAME", pg.username(),
                    "DATABASE_INSTANCES_MAIN_PASSWORD", pg.password()))
            .instantSource(now::get)
            .start();
    db = app.get(Databases.class).defaultInstance();
    useCases = app.get(UseCases.class);
    tickets = app.get(TicketUseCases.class);
  }

  @AfterEach
  void stop() {
    threads.close();
    app.close();
  }

  // ---- contexts: who is acting -----------------------------------------------------------------

  /** A requester: may open tickets and comment on their own. */
  Context requester(ClientId client, String id) {
    return Context.of(client).withPrincipal(new PrincipalId(id)).withPermissions(Set.of(Permissions.OPEN, Permissions.COMMENT, Permissions.READ));
  }

  /** An agent: works any ticket of the client. */
  Context agent(ClientId client) {
    return Context.of(client)
        .withPrincipal(new PrincipalId("agent_1"))
        .withPermissions(Set.of(Permissions.READ, Permissions.OPEN, Permissions.WORK, Permissions.COMMENT));
  }

  /** A lead: an agent who also sets the client's SLA. */
  Context lead(ClientId client) {
    return Context.of(client)
        .withPrincipal(new PrincipalId("lead_1"))
        .withPermissions(Set.of(Permissions.READ, Permissions.WORK, Permissions.COMMENT, Permissions.SLA_MANAGE));
  }

  /** What a receiver runs as: the application's service principal, no permissions. */
  Context service(ClientId client) {
    return Context.of(client).withPrincipal(new PrincipalId("prn_svc"), PrincipalKind.SERVICE);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  String open(ClientId client, Priority priority) {
    return committed(useCases.run(tickets.openTicket(), requester(client, "req_1"), new OpenTicket("Printer on fire", "It is smoking.", priority)))
        .ticketId();
  }

  static <E extends DomainEvent> E committed(UseCaseResult<E> r) {
    assertThat(r).isInstanceOf(UseCaseResult.Committed.class);
    return ((UseCaseResult.Committed<E>) r).event();
  }

  static UseCaseError failed(UseCaseResult<?> r) {
    assertThat(r).isInstanceOf(UseCaseResult.Failed.class);
    return ((UseCaseResult.Failed<?>) r).error();
  }

  int count(String sql) {
    return Integer.parseInt(pg.query(sql));
  }

  /** version:status:assignee:comments for one ticket. */
  String ticket(String id) {
    return pg.query(
        "SELECT version || ':' || status || ':' || coalesce(assignee_id, '-') || ':' "
            + "|| (SELECT count(*) FROM ticket_comments WHERE ticket_id = id) FROM tickets WHERE id = '"
            + id
            + "'");
  }

  // ---- opening and the SLA ---------------------------------------------------------------------

  @Test
  void aTicketIsOpenedWithTheDefaultSlaAndWritesOneEventAndOneAuditRow() {
    String id = open(acme, Priority.NORMAL);

    assertThat(ticket(id)).isEqualTo("1:OPEN:-:0");
    assertThat(pg.query("SELECT due_at AT TIME ZONE 'UTC' FROM tickets WHERE id = '" + id + "'").replace(' ', 'T'))
        .startsWith("2026-10-18T09:00:00"); // NORMAL: 1440 minutes
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE type = 'AUDIT_LOG'")).isEqualTo(1);
    assertThat(pg.query("SELECT payload::jsonb ->> 'type' FROM outbox_messages WHERE type = 'EVENT'"))
        .isEqualTo("tickets-trial:support:ticket:opened");
    assertThat(pg.query("SELECT payload::jsonb ->> 'clientCode' FROM outbox_messages WHERE type = 'EVENT'")).isEqualTo("acme");
  }

  @Test
  void aClientsOwnPolicyDecidesTheDeadlineOfTicketsOpenedAfterwardsButNotBefore() {
    String before = open(acme, Priority.URGENT);
    committed(useCases.run(tickets.setSlaPolicy(), lead(acme), new SetSlaPolicy(600, 300, 120, 15)));
    String after = open(acme, Priority.URGENT);
    String other = open(globex, Priority.URGENT);

    assertThat(minutesToDue(before)).isEqualTo(60); // the default, fixed when it was opened
    assertThat(minutesToDue(after)).isEqualTo(15);
    assertThat(minutesToDue(other)).isEqualTo(60); // another client's policy is not this client's
  }

  private long minutesToDue(String id) {
    return Long.parseLong(pg.query("SELECT extract(epoch FROM (due_at - opened_at)) / 60 FROM tickets WHERE id = '" + id + "'").replaceAll("\\..*", ""));
  }

  @Test
  void aPolicyIsOnePerClientAndASecondSetUpdatesIt() {
    committed(useCases.run(tickets.setSlaPolicy(), lead(acme), new SetSlaPolicy(600, 300, 120, 15)));
    committed(useCases.run(tickets.setSlaPolicy(), lead(acme), new SetSlaPolicy(700, 400, 130, 20)));

    assertThat(count("SELECT count(*) FROM sla_policies WHERE client_id = 'acme'")).isEqualTo(1);
    assertThat(pg.query("SELECT version || ':' || urgent_minutes FROM sla_policies WHERE client_id = 'acme'")).isEqualTo("2:20");
    assertThat(failed(useCases.run(tickets.setSlaPolicy(), lead(acme), new SetSlaPolicy(10, 20, 30, 40))).code())
        .isEqualTo("POLICY_NOT_ORDERED");
    assertThat(failed(useCases.run(tickets.setSlaPolicy(), lead(acme), new SetSlaPolicy(0, 20, 30, 40))).code())
        .isEqualTo("MINUTES_INVALID");
  }

  // ---- the status machine ----------------------------------------------------------------------

  @Test
  void aTicketGoesFromOpenToClosedAndTheMachineRefusesTheRest() {
    String id = open(acme, Priority.HIGH);
    Context agent = agent(acme);

    assertThat(failed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.START, null))).code())
        .isEqualTo("ASSIGNEE_REQUIRED");
    committed(useCases.run(tickets.assignTicket(), agent, new AssignTicket(id, "agent_1")));
    assertThat(failed(useCases.run(tickets.assignTicket(), agent, new AssignTicket(id, "agent_1"))).code()).isEqualTo("ALREADY_ASSIGNED");
    committed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.START, null)));
    assertThat(ticket(id)).isEqualTo("3:IN_PROGRESS:agent_1:0");

    UseCaseError illegal = failed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.CLOSE, null)));
    assertThat(illegal.code()).isEqualTo("ILLEGAL_TRANSITION");
    assertThat(illegal.kind()).isEqualTo(ErrorKind.BUSINESS_RULE);
    assertThat(illegal.details()).containsEntry("status", "IN_PROGRESS").containsEntry("action", "CLOSE");
    assertThat(failed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.RESOLVE, " "))).code())
        .isEqualTo("NOTE_REQUIRED");

    now.set(START.plusSeconds(600));
    committed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.RESOLVE, "Replaced the toner.")));
    assertThat(pg.query("SELECT resolution || '@' || (resolved_at AT TIME ZONE 'UTC') FROM tickets WHERE id = '" + id + "'")).startsWith("Replaced the toner.@2026-10-17 09:10:00");
    committed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.REOPEN, "Still smoking.")));
    assertThat(pg.query("SELECT coalesce(resolution, 'none') FROM tickets WHERE id = '" + id + "'")).isEqualTo("none");
    committed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.START, null)));
    committed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.RESOLVE, "Replaced the printer.")));
    committed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.CLOSE, null)));
    assertThat(ticket(id)).isEqualTo("8:CLOSED:agent_1:0");

    assertThat(failed(useCases.run(tickets.assignTicket(), agent, new AssignTicket(id, "agent_2"))).code()).isEqualTo("TICKET_NOT_ASSIGNABLE");
    assertThat(failed(useCases.run(tickets.changeStatus(), agent, new ChangeStatus(id, TicketAction.REOPEN, "no"))).code())
        .isEqualTo("ILLEGAL_TRANSITION");
    // every committed step wrote an event and an audit row
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'")).isEqualTo(8);
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE type = 'AUDIT_LOG'")).isEqualTo(8);
  }

  @Test
  void anAssignmentEventNamesTheNewAndThePreviousAssignee() {
    String id = open(acme, Priority.LOW);
    committed(useCases.run(tickets.assignTicket(), agent(acme), new AssignTicket(id, "agent_2")));
    committed(useCases.run(tickets.assignTicket(), agent(acme), new AssignTicket(id, "agent_3")));

    assertThat(
            pg.query(
                "SELECT (payload::jsonb ->> 'data')::jsonb ->> 'assigneeId' || '<-' || ((payload::jsonb ->> 'data')::jsonb ->> 'previousAssigneeId') "
                    + "FROM outbox_messages WHERE type = 'EVENT' AND payload::jsonb ->> 'type' LIKE '%:assigned' "
                    + "ORDER BY created_at DESC, id DESC LIMIT 1"))
        .isEqualTo("agent_3<-agent_2");
  }

  // ---- comments --------------------------------------------------------------------------------

  @Test
  void commentsAreAppendedInOrderAndARequesterCommentsOnlyOnTheirOwnTicket() {
    String id = open(acme, Priority.NORMAL);

    committed(useCases.run(tickets.addComment(), requester(acme, "req_1"), new AddComment(id, "Any news?")));
    committed(useCases.run(tickets.addComment(), agent(acme), new AddComment(id, "Looking now.")));
    UseCaseError notYours = failed(useCases.run(tickets.addComment(), requester(acme, "req_2"), new AddComment(id, "Me too")));

    assertThat(notYours.kind()).isEqualTo(ErrorKind.AUTHORIZATION);
    assertThat(notYours.code()).isEqualTo("NOT_YOUR_TICKET");
    assertThat(ticket(id)).isEqualTo("3:OPEN:-:2");
    assertThat(pg.query("SELECT string_agg(position || author_id, ',' ORDER BY position) FROM ticket_comments")).isEqualTo("0req_1,1agent_1");
    assertThat(failed(useCases.run(tickets.addComment(), agent(acme), new AddComment(id, "  "))).code()).isEqualTo("BODY_INVALID");

    committed(useCases.run(tickets.assignTicket(), agent(acme), new AssignTicket(id, "agent_1")));
    committed(useCases.run(tickets.changeStatus(), agent(acme), new ChangeStatus(id, TicketAction.START, null)));
    committed(useCases.run(tickets.changeStatus(), agent(acme), new ChangeStatus(id, TicketAction.RESOLVE, "Done")));
    committed(useCases.run(tickets.changeStatus(), agent(acme), new ChangeStatus(id, TicketAction.CLOSE, null)));
    assertThat(failed(useCases.run(tickets.addComment(), agent(acme), new AddComment(id, "Late"))).code()).isEqualTo("TICKET_CLOSED");
  }

  // ---- authorization and clients ---------------------------------------------------------------

  @Test
  void aUseCaseChecksTheCallersPermissionAndTheKindOfPrincipalItNeeds() {
    String id = open(acme, Priority.NORMAL);
    int outbox = count("SELECT count(*) FROM outbox_messages");

    assertThat(failed(useCases.run(tickets.openTicket(), Context.of(acme), new OpenTicket("x", "", Priority.LOW))).code()).isEqualTo("PRINCIPAL_REQUIRED");
    assertThat(failed(useCases.run(tickets.assignTicket(), requester(acme, "req_1"), new AssignTicket(id, "req_1"))).code()).isEqualTo("FORBIDDEN");
    assertThat(failed(useCases.run(tickets.setSlaPolicy(), requester(acme, "req_1"), new SetSlaPolicy(4, 3, 2, 1))).kind()).isEqualTo(ErrorKind.AUTHORIZATION);
    // the sweep's use case is the service's only: not even an agent may flag a ticket
    assertThat(failed(useCases.run(tickets.flagSlaBreach(), agent(acme), new FlagSlaBreach(id))).code()).isEqualTo("NOT_A_SERVICE");
    assertThat(count("SELECT count(*) FROM outbox_messages")).isEqualTo(outbox);
    assertThat(ticket(id)).isEqualTo("1:OPEN:-:0");
  }

  @Test
  void anotherClientCannotSeeOrChangeTheTicketAndListsAreTheClientsOwn() throws Exception {
    String id = open(acme, Priority.NORMAL);
    open(globex, Priority.NORMAL);

    assertThat(failed(useCases.run(tickets.assignTicket(), agent(globex), new AssignTicket(id, "agent_1"))).code()).isEqualTo("TICKET_NOT_FOUND");
    assertThat(failed(useCases.run(tickets.addComment(), agent(globex), new AddComment(id, "mine"))).code()).isEqualTo("TICKET_NOT_FOUND");
    assertThat(ticket(id)).isEqualTo("1:OPEN:-:0");
    assertThat(TicketQueries.list(db, acme, null, null, null, null, 50).items()).extracting(TicketQueries.Summary::id).containsExactly(id);
    assertThat(TicketQueries.list(db, globex, null, null, null, null, 50).items()).hasSize(1);
  }

  // ---- reading ---------------------------------------------------------------------------------

  @Test
  void theListFiltersAndPagesNewestFirst() throws Exception {
    String a = open(acme, Priority.LOW);
    String b = open(acme, Priority.NORMAL);
    String c = open(acme, Priority.HIGH);
    committed(useCases.run(tickets.assignTicket(), agent(acme), new AssignTicket(b, "agent_7")));

    TicketQueries.Page first = TicketQueries.list(db, acme, null, null, null, null, 2);
    assertThat(first.items()).extracting(TicketQueries.Summary::id).containsExactly(c, b);
    assertThat(first.nextCursor()).isEqualTo(b);
    TicketQueries.Page second = TicketQueries.list(db, acme, null, null, null, first.nextCursor(), 2);
    assertThat(second.items()).extracting(TicketQueries.Summary::id).containsExactly(a);
    assertThat(second.nextCursor()).isNull();
    assertThat(TicketQueries.list(db, acme, null, "agent_7", null, null, 50).items()).extracting(TicketQueries.Summary::id).containsExactly(b);
    assertThat(TicketQueries.list(db, acme, TicketStatus.IN_PROGRESS, null, null, null, 50).items()).isEmpty();
    assertThat(TicketQueries.list(db, acme, TicketStatus.OPEN, null, "req_1", null, 50).items()).hasSize(3);
    assertThat(TicketQueries.counts(db, acme)).containsExactly(new TicketQueries.StatusCount(TicketStatus.OPEN, 3, 0));
  }

  // ---- the SLA sweep's use case ----------------------------------------------------------------

  @Test
  void aTicketPastItsDeadlineIsFlaggedOnceAndOnlyWhileSomeoneStillOwesAnAnswer() throws Exception {
    String acmeUrgent = open(acme, Priority.URGENT); // due in 60 minutes
    String globexUrgent = open(globex, Priority.URGENT);
    String resolved = open(acme, Priority.URGENT);
    committed(useCases.run(tickets.assignTicket(), agent(acme), new AssignTicket(resolved, "agent_1")));
    committed(useCases.run(tickets.changeStatus(), agent(acme), new ChangeStatus(resolved, TicketAction.START, null)));
    committed(useCases.run(tickets.changeStatus(), agent(acme), new ChangeStatus(resolved, TicketAction.RESOLVE, "Done in time")));

    assertThat(TicketQueries.overdue(db, now.get(), 100)).isEmpty();
    assertThat(failed(useCases.run(tickets.flagSlaBreach(), service(acme), new FlagSlaBreach(acmeUrgent))).code()).isEqualTo("NOT_OVERDUE");

    now.set(START.plus(Duration.ofMinutes(61)));
    List<TicketQueries.Overdue> overdue = TicketQueries.overdue(db, now.get(), 100);
    assertThat(overdue).extracting(o -> o.id().value()).containsExactlyInAnyOrder(acmeUrgent, globexUrgent); // both clients, not the resolved one
    for (TicketQueries.Overdue o : overdue) {
      // the sweep acts for each ticket's own client
      committed(useCases.run(tickets.flagSlaBreach(), service(o.client()), new FlagSlaBreach(o.id().value())));
    }
    assertThat(TicketQueries.overdue(db, now.get(), 100)).isEmpty();
    assertThat(failed(useCases.run(tickets.flagSlaBreach(), service(acme), new FlagSlaBreach(acmeUrgent))).code()).isEqualTo("NOT_OVERDUE");
    assertThat(failed(useCases.run(tickets.flagSlaBreach(), service(globex), new FlagSlaBreach(acmeUrgent))).code()).isEqualTo("TICKET_NOT_FOUND");
    assertThat(count("SELECT count(*) FROM outbox_messages WHERE payload::jsonb ->> 'type' = 'tickets-trial:support:ticket:sla-breached'")).isEqualTo(2);
    assertThat(TicketQueries.totals(db)).containsExactly(2, 2);
  }

  // ---- atomicity and optimistic locking ---------------------------------------------------------

  @Test
  void aDatabaseFailureWhileWritingTheCommentRollsBackTheTicketAndTheOutbox() {
    String id = open(acme, Priority.NORMAL);
    int outbox = count("SELECT count(*) FROM outbox_messages");

    // A rule only the database knows: validate passes, the comment insert fails after the ticket
    // row was updated.
    pg.execute("ALTER TABLE ticket_comments ADD CONSTRAINT no_boom CHECK (body <> 'boom')");
    UseCaseError error = failed(useCases.run(tickets.addComment(), agent(acme), new AddComment(id, "boom")));

    assertThat(error.kind()).isEqualTo(ErrorKind.INTERNAL);
    assertThat(error.code()).isEqualTo("PERSIST");
    assertThat(error.message()).doesNotContain("boom").doesNotContain("ticket_comments");
    assertThat(ticket(id)).isEqualTo("1:OPEN:-:0");
    assertThat(count("SELECT count(*) FROM outbox_messages")).isEqualTo(outbox);
  }

  /** Holds the first load of a ticket until the test lets the attempt go on. */
  static final class GatedRepository extends TicketRepository {
    final CountDownLatch loaded = new CountDownLatch(1);
    final CountDownLatch proceed = new CountDownLatch(1);
    final AtomicBoolean first = new AtomicBoolean(true);

    @Override
    public Optional<Ticket> find(Database db, ClientId client, TicketId id) throws SQLException {
      Optional<Ticket> found = super.find(db, client, id);
      if (first.getAndSet(false)) {
        loaded.countDown();
        try {
          if (!proceed.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test never released the first attempt");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return found;
    }
  }

  private TicketUseCases gated(GatedRepository repository, TicketUseCases.Policies policies) {
    return new TicketUseCases(
        repository, new SlaPolicyRepository(), db, app.get(Events.class), app.get(Ids.class), now::get, policies);
  }

  @Test
  void twoCommentsPostedAtOnceBothLandUnderRetryAtTheNextFreePositions() throws Exception {
    String id = open(acme, Priority.NORMAL);
    GatedRepository repository = new GatedRepository();
    ConflictPolicy retry = ConflictPolicy.retry(3, Duration.ofMillis(10));
    TicketUseCases slow = gated(repository, new TicketUseCases.Policies(retry, retry, retry, retry, retry));

    Future<UseCaseResult<TicketCommented>> a =
        threads.submit(() -> useCases.run(slow.addComment(), agent(acme), new AddComment(id, "from A")));
    assertThat(repository.loaded.await(20, TimeUnit.SECONDS)).isTrue();
    committed(useCases.run(tickets.addComment(), agent(acme), new AddComment(id, "from B")));
    repository.proceed.countDown();

    committed(a.get(20, TimeUnit.SECONDS));
    assertThat(ticket(id)).isEqualTo("3:OPEN:-:2");
    assertThat(pg.query("SELECT string_agg(body, ',' ORDER BY position) FROM ticket_comments")).isEqualTo("from B,from A");
  }

  @Test
  void aStatusChangeOnAStaleViewIsRefusedAndLeavesTheOtherChangeAlone() throws Exception {
    String id = open(acme, Priority.NORMAL);
    committed(useCases.run(tickets.assignTicket(), agent(acme), new AssignTicket(id, "agent_1")));
    GatedRepository repository = new GatedRepository();
    ConflictPolicy fail = ConflictPolicy.fail();
    TicketUseCases slow = gated(repository, new TicketUseCases.Policies(fail, fail, fail, fail, fail));

    Future<UseCaseResult<TicketEvents.TicketStatusChanged>> start =
        threads.submit(() -> useCases.run(slow.changeStatus(), agent(acme), new ChangeStatus(id, TicketAction.START, null)));
    assertThat(repository.loaded.await(20, TimeUnit.SECONDS)).isTrue();
    committed(useCases.run(tickets.addComment(), agent(acme), new AddComment(id, "while you were looking")));
    int outbox = count("SELECT count(*) FROM outbox_messages");
    repository.proceed.countDown();

    UseCaseError error = failed(start.get(20, TimeUnit.SECONDS));
    assertThat(error.kind()).isEqualTo(ErrorKind.CONFLICT);
    assertThat(error.code()).isEqualTo("STALE_AGGREGATE");
    assertThat(ticket(id)).isEqualTo("3:OPEN:agent_1:1");
    assertThat(count("SELECT count(*) FROM outbox_messages")).isEqualTo(outbox);
  }
}
