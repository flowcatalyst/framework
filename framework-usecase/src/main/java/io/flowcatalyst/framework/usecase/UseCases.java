package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.metrics.Counter;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.Timer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runner: applies a use case's plan atomically (write-path contract, section 4). It is the
 * only place that begins, commits or rolls back a transaction.
 *
 * <p>One attempt validates, authorizes, executes, checks the plan belongs to the context's
 * client, then in one READ COMMITTED transaction on the default database applies the writes in
 * order, writes the events and the audit row, and commits. A stale aggregate, a serialization
 * failure or a deadlock is a conflict, handled by the use case's {@link ConflictPolicy}.
 * Thread-safe.
 */
public final class UseCases {

  private static final Logger LOG = LoggerFactory.getLogger(UseCases.class);

  private static final String SERIALIZATION_FAILURE = "40001";
  private static final String DEADLOCK_DETECTED = "40P01";
  private static final String UNIQUE_VIOLATION = "23505";

  /** Waits between attempts of a retried use case. Tests replace it. */
  @FunctionalInterface
  public interface Sleeper {

    /**
     * Waits.
     *
     * @param duration how long
     * @throws InterruptedException when interrupted
     */
    void sleep(Duration duration) throws InterruptedException;
  }

  private final Database database;
  private final Sink sink;
  private final InstantSource clock;
  private final Sleeper sleeper;
  private final MonotonicClock monotonic;
  private final Counter runs;
  private final Timer durations;

  /**
   * Creates a runner.
   *
   * @param database the database the writes go to (the default instance)
   * @param sink where events and the audit row go
   * @param clock the wall-clock source
   */
  public UseCases(Database database, Sink sink, InstantSource clock) {
    this(database, sink, clock, d -> Thread.sleep(d));
  }

  /**
   * Creates a runner with its own way of waiting between attempts.
   *
   * @param database the database the writes go to
   * @param sink where events and the audit row go
   * @param clock the wall-clock source
   * @param sleeper waits between attempts
   */
  public UseCases(Database database, Sink sink, InstantSource clock, Sleeper sleeper) {
    this(database, sink, clock, sleeper, Metrics.noop(), MonotonicClock.system());
  }

  /**
   * Creates a runner that records {@code fw_usecase_runs_total} and
   * {@code fw_usecase_duration_seconds} (background-and-metrics contract, section 2).
   *
   * @param database the database the writes go to
   * @param sink where events and the audit row go
   * @param clock the wall-clock source
   * @param sleeper waits between attempts
   * @param metrics where the metrics go
   * @param monotonic measures durations
   */
  public UseCases(
      Database database,
      Sink sink,
      InstantSource clock,
      Sleeper sleeper,
      Metrics metrics,
      MonotonicClock monotonic) {
    this.database = database;
    this.sink = sink;
    this.clock = clock;
    this.sleeper = sleeper;
    this.monotonic = monotonic;
    this.runs =
        metrics.counter(
            "fw_usecase_runs_total",
            "Use case runs by use case and outcome: committed, or the failure's kind.",
            "usecase",
            "outcome");
    this.durations =
        metrics.timer(
            "fw_usecase_duration_seconds",
            "Use case run duration, all attempts included.",
            "usecase");
  }

  /**
   * Runs a use case. Never throws for an expected outcome.
   *
   * @param <C> the command type
   * @param <E> the result event type
   * @param useCase the use case
   * @param context who is acting, for which client
   * @param command the command
   * @return {@link UseCaseResult.Committed} with the result event, or {@link UseCaseResult.Failed}
   */
  public <C, E extends DomainEvent> UseCaseResult<E> run(
      UseCase<C, E> useCase, Context context, C command) {
    return runCounted(useCase, context, command, null);
  }

  /**
   * Runs a use case <em>inside the caller's transaction</em> instead of its own: the plan's writes,
   * the events and the audit row go on {@code joined}'s connection and are committed (or rolled back)
   * when the caller commits (or rolls back). {@link UseCaseResult.Committed} then means "applied in
   * your transaction". This is how a use case composes with work that owns the transaction, such as
   * {@code DispatchDelivery.once} (write-path contract, section 4a).
   *
   * <p>Each attempt runs under a savepoint: a failed or conflicting attempt is rolled back to it, so
   * the caller's earlier work is kept and the transaction stays usable, and the conflict policy
   * applies as usual. The connection must be in a transaction (auto-commit off). The runner never
   * commits or rolls back the caller's transaction.
   *
   * @param <C> the command type
   * @param <E> the result event type
   * @param useCase the use case
   * @param context who is acting, for which client
   * @param command the command
   * @param joined the caller's transaction ({@link Tx#of})
   * @return {@link UseCaseResult.Committed} with the result event, or {@link UseCaseResult.Failed}
   * @throws IllegalArgumentException when the connection is in auto-commit mode
   */
  public <C, E extends DomainEvent> UseCaseResult<E> run(
      UseCase<C, E> useCase, Context context, C command, Tx joined) {
    try {
      if (joined.connection().getAutoCommit()) {
        throw new IllegalArgumentException(
            "a joined run needs a connection in a transaction (auto-commit off)");
      }
    } catch (SQLException e) {
      throw new IllegalArgumentException("the joined connection is not usable", e);
    }
    return runCounted(useCase, context, command, joined);
  }

  private <C, E extends DomainEvent> UseCaseResult<E> runCounted(
      UseCase<C, E> useCase, Context context, C command, @Nullable Tx joined) {
    long start = monotonic.nanos();
    UseCaseResult<E> result = runAttempts(useCase, context, command, joined);
    String outcome =
        switch (result) {
          case UseCaseResult.Committed<E> c -> "committed";
          case UseCaseResult.Failed<E> f -> f.error().kind().name().toLowerCase(Locale.ROOT);
        };
    runs.labels(useCase.name(), outcome).inc();
    durations.labels(useCase.name()).recordNanos(monotonic.nanos() - start);
    return result;
  }

  private <C, E extends DomainEvent> UseCaseResult<E> runAttempts(
      UseCase<C, E> useCase, Context context, C command, @Nullable Tx joined) {
    ConflictPolicy policy = useCase.conflict();
    for (int attempt = 1; ; attempt++) {
      Attempt<E> outcome = attempt(useCase, context, command, joined);
      if (outcome instanceof Done<E> done) {
        return done.result();
      }
      if (attempt >= policy.maxAttempts()) {
        LOG.info(
            "use case {} conflicted on attempt {} of {}; giving up",
            useCase.name(),
            attempt,
            policy.maxAttempts());
        return failed(staleAggregate());
      }
      LOG.debug("use case {} conflicted on attempt {}; running again", useCase.name(), attempt);
      Duration wait = jittered(policy.backoff());
      try {
        sleeper.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return failed(UseCaseError.internal("INTERRUPTED"));
      }
    }
  }

  // ---- one attempt -------------------------------------------------------------------------

  private sealed interface Attempt<E extends DomainEvent> permits Done, Conflict {}

  private record Done<E extends DomainEvent>(UseCaseResult<E> result) implements Attempt<E> {}

  private record Conflict<E extends DomainEvent>() implements Attempt<E> {}

  private <C, E extends DomainEvent> Attempt<E> attempt(
      UseCase<C, E> useCase, Context context, C command, @Nullable Tx joined) {
    String step = "VALIDATE";
    Plan<E> plan;
    try {
      Optional<Check<? super C>> validate = useCase.validate();
      if (validate.isPresent()) {
        Optional<UseCaseError> error = validate.get().check(context, command);
        if (error.isPresent()) {
          return done(failed(error.get()));
        }
      }
      step = "AUTHORIZE";
      Optional<UseCaseError> denied = useCase.authorize().check(context, command);
      if (denied.isPresent()) {
        return done(failed(denied.get()));
      }
      step = "EXECUTE";
      switch (useCase.execute().execute(context, command)) {
        case Rejected<E> rejected -> {
          return done(failed(rejected.error()));
        }
        case Plan<E> decided -> plan = decided;
      }
    } catch (PhaseException e) {
      return failure(useCase, e.step(), e.failure());
    } catch (Exception e) {
      return failure(useCase, step, e);
    }

    if (!belongsToClient(plan, context)) {
      LOG.error(
          "BUG: use case {} planned a write for an aggregate of another client than {}",
          useCase.name(),
          context.client());
      return done(failed(UseCaseError.internal("CROSS_CLIENT_WRITE")));
    }
    return joined == null
        ? persist(useCase, context, command, plan)
        : persistJoined(useCase, context, command, plan, joined);
  }

  private <C, E extends DomainEvent> Attempt<E> persist(
      UseCase<C, E> useCase, Context context, C command, Plan<E> plan) {
    String[] step = {"BEGIN"};
    try (Connection connection = database.dataSource().getConnection()) {
      boolean committed = false;
      try {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        if (!write(useCase, context, command, plan, new Tx(connection), step)) {
          return new Conflict<>();
        }
        step[0] = "COMMIT";
        connection.commit();
        committed = true;
        return done(new UseCaseResult.Committed<>(plan.result()));
      } finally {
        if (!committed) {
          rollbackQuietly(connection);
        }
      }
    } catch (Exception e) {
      return failure(useCase, step[0], e);
    }
  }

  /**
   * The same writes inside the caller's transaction, under a savepoint so that a failed or
   * conflicting attempt leaves the caller's earlier work intact.
   */
  private <C, E extends DomainEvent> Attempt<E> persistJoined(
      UseCase<C, E> useCase, Context context, C command, Plan<E> plan, Tx joined) {
    String[] step = {"BEGIN"};
    Connection connection = joined.connection();
    @Nullable Savepoint savepoint = null;
    try {
      savepoint = connection.setSavepoint();
      if (!write(useCase, context, command, plan, joined, step)) {
        rollbackToQuietly(connection, savepoint);
        return new Conflict<>();
      }
      step[0] = "SAVEPOINT";
      connection.releaseSavepoint(savepoint);
      return done(new UseCaseResult.Committed<>(plan.result()));
    } catch (Exception e) {
      if (savepoint != null) {
        rollbackToQuietly(connection, savepoint);
      }
      return failure(useCase, step[0], e);
    }
  }

  /** Applies the plan, writes the events and the audit row; false when an aggregate is stale. */
  private <C, E extends DomainEvent> boolean write(
      UseCase<C, E> useCase, Context context, C command, Plan<E> plan, Tx tx, String[] step)
      throws Exception {
    step[0] = "PERSIST";
    for (Plan.Step planned : plan.steps()) {
      if (!apply(planned, tx)) {
        return false;
      }
    }

    step[0] = "OUTBOX";
    List<DomainEvent> events = new ArrayList<>(plan.steps().size());
    for (Plan.Step planned : plan.steps()) {
      events.add(planned.event());
    }
    sink.writeEvents(tx, context, events);
    if (!plan.dispatchJobs().isEmpty()) {
      sink.writeDispatchJobs(tx, context, plan.dispatchJobs());
    }

    step[0] = "AUDIT";
    sink.writeAudit(tx, auditEntry(useCase.name(), context, command, plan));
    return true;
  }

  /** Applies one planned write; false when the aggregate is stale. */
  private static boolean apply(Plan.Step step, Tx tx) throws SQLException {
    return switch (step) {
      case Plan.Save<?> save -> save(save, tx);
      case Plan.Delete<?> delete -> delete(delete, tx);
      case Plan.Emit emit -> true;
    };
  }

  private static <A extends Aggregate> boolean save(Plan.Save<A> step, Tx tx) throws SQLException {
    A aggregate = step.aggregate();
    if (aggregate.version() == 0) {
      step.repository().insert(tx, aggregate);
      return true;
    }
    return exactlyOne(step.repository().update(tx, aggregate), "update", aggregate);
  }

  private static <A extends Aggregate> boolean delete(Plan.Delete<A> step, Tx tx)
      throws SQLException {
    A aggregate = step.aggregate();
    if (aggregate.version() == 0) {
      throw new IllegalStateException(
          "cannot delete " + aggregate.id().value() + ": it was never stored (version 0)");
    }
    return exactlyOne(step.repository().delete(tx, aggregate), "delete", aggregate);
  }

  private static boolean exactlyOne(int rows, String operation, Aggregate aggregate) {
    if (rows == 0) {
      return false;
    }
    if (rows > 1) {
      throw new IllegalStateException(
          operation + " of " + aggregate.id().value() + " affected " + rows + " rows");
    }
    return true;
  }

  private static boolean belongsToClient(Plan<?> plan, Context context) {
    for (Plan.Step step : plan.steps()) {
      Aggregate aggregate =
          switch (step) {
            case Plan.Save<?> save -> save.aggregate();
            case Plan.Delete<?> delete -> delete.aggregate();
            case Plan.Emit emit -> null;
          };
      if (aggregate != null && !aggregate.client().equals(context.client())) {
        return false;
      }
    }
    return true;
  }

  private AuditEntry auditEntry(String operation, Context context, Object command, Plan<?> plan) {
    String entityType = null;
    String entityId = null;
    for (Plan.Step step : plan.steps()) {
      Aggregate aggregate =
          switch (step) {
            case Plan.Save<?> save -> save.aggregate();
            case Plan.Delete<?> delete -> delete.aggregate();
            case Plan.Emit emit -> null;
          };
      if (aggregate != null) {
        entityType = aggregate.getClass().getSimpleName();
        entityId = aggregate.id().value();
        break;
      }
    }
    EventMetadata first = plan.result().metadata();
    if (entityType == null || entityId == null) {
      // domain.aggregate.id, where the id may itself contain dots
      String[] parts = first.subject().split("\\.", 3);
      entityType = parts.length == 3 ? parts[1] : first.subject();
      entityId = parts.length == 3 ? parts[2] : first.subject();
    }
    Map<String, Object> data = null;
    if (command instanceof AuditData audited) {
      data = new LinkedHashMap<>(audited.auditData());
    }
    return new AuditEntry(
        entityType,
        entityId,
        operation,
        data,
        context.principal() == null ? null : context.principal().value(),
        clock.instant(),
        first.source(),
        context.correlationId().value(),
        context.application() == null ? null : context.application().value(),
        context.client());
  }

  // ---- failures ----------------------------------------------------------------------------

  private <C, E extends DomainEvent> Attempt<E> failure(
      UseCase<C, E> useCase, String step, Throwable cause) {
    SQLException sql = firstSqlException(cause);
    if (sql != null) {
      String state = sql.getSQLState();
      if (SERIALIZATION_FAILURE.equals(state) || DEADLOCK_DETECTED.equals(state)) {
        LOG.debug("use case {} hit SQLSTATE {} at {}", useCase.name(), state, step);
        return new Conflict<>();
      }
      if (UNIQUE_VIOLATION.equals(state)) {
        LOG.debug("use case {} violated a unique constraint at {}", useCase.name(), step);
        return done(failed(uniqueViolation(sql)));
      }
    }
    LOG.error("use case {} failed at step {}", useCase.name(), step, cause);
    return done(failed(UseCaseError.internal(step)));
  }

  private static @Nullable SQLException firstSqlException(Throwable start) {
    Throwable t = start;
    for (int depth = 0; t != null && depth < 20; depth++) {
      if (t instanceof SQLException sql) {
        return sql;
      }
      t = t.getCause();
    }
    return null;
  }

  private static UseCaseError uniqueViolation(SQLException sql) {
    Map<String, String> details = new LinkedHashMap<>();
    if (sql instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
      String constraint = psql.getServerErrorMessage().getConstraint();
      if (constraint != null) {
        details.put("constraint", constraint);
      }
    }
    return new UseCaseError(
        ErrorKind.CONFLICT,
        "UNIQUE_VIOLATION",
        "A record with the same unique value already exists.",
        details);
  }

  private static UseCaseError staleAggregate() {
    return UseCaseError.conflict(
        "STALE_AGGREGATE", "The record was changed by someone else; reload it and try again.");
  }

  private static <E extends DomainEvent> UseCaseResult<E> failed(UseCaseError error) {
    return new UseCaseResult.Failed<>(error);
  }

  private static <E extends DomainEvent> Attempt<E> done(UseCaseResult<E> result) {
    return new Done<>(result);
  }

  private static void rollbackToQuietly(Connection connection, Savepoint savepoint) {
    try {
      connection.rollback(savepoint);
    } catch (SQLException e) {
      LOG.warn("rollback to savepoint failed", e);
    }
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException e) {
      LOG.warn("rollback failed", e);
    }
  }

  private static Duration jittered(Duration backoff) {
    double factor = 0.5 + ThreadLocalRandom.current().nextDouble();
    return Duration.ofNanos((long) (backoff.toNanos() * factor));
  }
}
