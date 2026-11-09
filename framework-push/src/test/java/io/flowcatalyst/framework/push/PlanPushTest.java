package io.flowcatalyst.framework.push;

import static io.flowcatalyst.framework.push.TestNode.ACME;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.database.PostgresFixture;
import io.flowcatalyst.framework.usecase.AggregateId;
import io.flowcatalyst.framework.usecase.Authorize;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.EventMetadata;
import io.flowcatalyst.framework.usecase.EventType;
import io.flowcatalyst.framework.usecase.Events;
import io.flowcatalyst.framework.usecase.Plan;
import io.flowcatalyst.framework.usecase.Push;
import io.flowcatalyst.framework.usecase.UseCase;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code plan.andPush} through the real runner, the relay, the hub and a stream (push contract section 2). */
class PlanPushTest {

  record Tick(EventMetadata metadata) implements DomainEvent {}

  record Command(String id, int version) {}

  record TickId(String value) implements AggregateId {}

  static final EventType TICKED = new EventType("test", "stock", "thing", "ticked");

  static PostgresFixture pg;

  @TempDir Path dir;

  @BeforeAll
  static void startPostgres() {
    pg = PostgresFixture.start();
  }

  @AfterAll
  static void stop() {
    pg.close();
  }

  @Test
  void aPushInAPlanReachesTheStreamOnlyWhenTheTransactionCommits() throws Exception {
    pg.reset();
    try (TestNode n = TestNode.start(pg, dir, TestNode.Config.defaults().withUseCases().withEnv("PUSH_MAX_DATA_BYTES", "64"))) {
      Events events = n.app.get(Events.class);
      UseCases useCases = n.app.get(UseCases.class);
      UseCase<Command, Tick> tick =
          UseCase.named("tick", Command.class, Tick.class)
              .authorize(Authorize.PUBLIC)
              .execute(
                  (c, cmd) -> {
                    Plan<Tick> plan = Plan.emit(new Tick(events.metadata(c, TICKED, new TickId(cmd.id()))));
                    return plan.andPush(Push.of("test:order:" + cmd.id(), "order.updated", Map.of("orderId", cmd.id(), "version", cmd.version())));
                  });
      Sse stream = n.open("acme", List.of("test:order:1"));

      // the module told the runner push.max-data-bytes: 64 here
      UseCaseResult<Tick> tooBig =
          useCases.run(
              UseCase.named("big", Command.class, Tick.class)
                  .authorize(Authorize.PUBLIC)
                  .execute((c, cmd) -> Plan.emit(new Tick(events.metadata(c, TICKED, new TickId(cmd.id())))).andPush(Push.of("test:order:1", "order.updated", Map.of("s", "x".repeat(60))))),
              Context.of(ACME),
              new Command("1", 1));
      assertThat(tooBig).isInstanceOf(UseCaseResult.Failed.class);
      assertThat(((UseCaseResult.Failed<?>) tooBig).error().code()).isEqualTo("INVALID_PUSH");
      assertThat(n.query("SELECT count(*) FROM fw_push_outbox")).containsExactly("0");

      UseCaseResult<Tick> ok = useCases.run(tick, Context.of(ACME), new Command("1", 7));
      assertThat(ok).isInstanceOf(UseCaseResult.Committed.class);
      String frame = stream.next();
      assertThat(frame).isEqualTo("id: 1\nevent: order.updated\ndata: {\"topic\":\"test:order:1\",\"data\":{\"orderId\":\"1\",\"version\":7}}\n\n");
    }
  }
}
