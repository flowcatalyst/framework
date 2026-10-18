package io.flowcatalyst.framework.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UseCaseDefinitionTest {

  record Cmd(String id) {}

  private static UseCase.Builder<Cmd, ThingEvent> builder() {
    return UseCase.named("do-thing", Cmd.class, ThingEvent.class);
  }

  @Test
  void aUseCaseWithoutAuthorizeFailsWhenItIsDefined() {
    assertThatIllegalStateException()
        .isThrownBy(() -> builder().execute((ctx, cmd) -> Decision.reject(UseCaseError.internal("X"))))
        .withMessageContaining("do-thing")
        .withMessageContaining("authorize");
  }

  @Test
  void validateAloneDoesNotSatisfyAuthorize() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                builder()
                    .validate((ctx, cmd) -> Optional.empty())
                    .execute((ctx, cmd) -> Decision.reject(UseCaseError.internal("X"))));
  }

  @Test
  void publicIsAnAcceptedAuthorization() throws Exception {
    UseCase<Cmd, ThingEvent> useCase =
        builder()
            .authorize(Authorize.PUBLIC)
            .execute((ctx, cmd) -> Decision.reject(UseCaseError.internal("X")));
    assertThat(useCase.name()).isEqualTo("do-thing");
    assertThat(useCase.conflict().maxAttempts()).isEqualTo(1);
    assertThat(useCase.validate()).isEmpty();
    assertThat(Authorize.PUBLIC.check(Context.of(new ClientId("a")), new Object())).isEmpty();
  }

  @Test
  void namesMustBeLowercaseKebab() {
    for (String bad : new String[] {"", "Do", "1do", "do_thing", "do thing", "-do"}) {
      assertThatIllegalArgumentException()
          .as(bad)
          .isThrownBy(() -> UseCase.named(bad, Cmd.class, ThingEvent.class));
    }
  }

  @Test
  void conflictPolicyDefaultsToFailAndRetryIsBounded() {
    assertThat(ConflictPolicy.fail().maxAttempts()).isEqualTo(1);
    assertThat(ConflictPolicy.retry(2, Duration.ZERO).maxAttempts()).isEqualTo(2);
    assertThat(ConflictPolicy.retry(10, Duration.ofMillis(5)).backoff())
        .isEqualTo(Duration.ofMillis(5));
    for (int bad : new int[] {-1, 0, 1, 11}) {
      assertThatIllegalArgumentException()
          .as("attempts " + bad)
          .isThrownBy(() -> ConflictPolicy.retry(bad, Duration.ZERO));
    }
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ConflictPolicy.retry(3, Duration.ofMillis(-1)));
  }

  @Test
  void errorCodesAreUpperSnakeAndKindsMapToHttpStatus() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> UseCaseError.validation("bad_code", "x"));
    assertThat(UseCaseError.validation("A", "m").httpStatus()).isEqualTo(400);
    assertThat(UseCaseError.authorization("A", "m").httpStatus()).isEqualTo(403);
    assertThat(UseCaseError.notFound("A", "m").httpStatus()).isEqualTo(404);
    assertThat(UseCaseError.conflict("A", "m").httpStatus()).isEqualTo(409);
    assertThat(UseCaseError.businessRule("A", "m").httpStatus()).isEqualTo(409);
    assertThat(UseCaseError.internal("PERSIST").httpStatus()).isEqualTo(500);
  }

  @Test
  void aPlanCarriesAtLeastOneEventAndKeepsTheFirstAsResult() {
    EventMetadata meta =
        new EventMetadata(
            "e", "1.0", "a:b:c:d", "a:b", "b.c.1", java.time.Instant.EPOCH, "corr", null, null, null, "g");
    ThingEvent first = new ThingEvent(meta, "1", "n", "c");
    ThingEvent second = new ThingEvent(meta, "2", "n", "c");
    Plan<ThingEvent> plan = Plan.emit(first).andEmit(second);
    assertThat(plan.result()).isSameAs(first);
    assertThat(plan.steps()).hasSize(2);
    assertThatIllegalArgumentException().isThrownBy(() -> new Plan<>(first, java.util.List.of()));
  }

  @Test
  void eventTypesDeriveSourceSubjectAndGroup() {
    EventType type = new EventType("orders", "sales", "order", "line-added");
    ThingId id = new ThingId("ord_1");
    assertThat(type.type()).isEqualTo("orders:sales:order:line-added");
    assertThat(type.source()).isEqualTo("orders:sales");
    assertThat(type.subject(id)).isEqualTo("sales.order.ord_1");
    assertThat(type.messageGroup(id)).isEqualTo("sales:order:ord_1");
    assertThatIllegalArgumentException().isThrownBy(() -> new EventType("Orders", "s", "o", "c"));
    assertThatIllegalArgumentException().isThrownBy(() -> new EventType("o", "s:x", "o", "c"));
  }

  @Test
  void aLoadPhaseDoesNotReplaceAuthorize() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                builder()
                    .load((ctx, cmd) -> Optional.of("x"), UseCaseError.notFound("GONE", "gone"))
                    .authorizeLoaded((ctx, cmd, loaded) -> Optional.empty())
                    .execute((ctx, cmd, loaded) -> Decision.reject(UseCaseError.internal("X"))))
        .withMessageContaining("authorize");
  }

  @Test
  void aRecordCheckIsOptionalAfterALoad() {
    UseCase<Cmd, ThingEvent> useCase =
        builder()
            .authorize(Authorize.PUBLIC)
            .load((ctx, cmd) -> Optional.of("x"), UseCaseError.notFound("GONE", "gone"))
            .execute((ctx, cmd, loaded) -> Decision.reject(UseCaseError.internal("X")));
    assertThat(useCase.name()).isEqualTo("do-thing");
  }
}
