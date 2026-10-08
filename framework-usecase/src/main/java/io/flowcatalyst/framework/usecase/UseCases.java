package io.flowcatalyst.framework.usecase;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.database.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    this.database = database;
    this.sink = sink;
    this.clock = clock;
    this.sleeper = sleeper;
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
    ConflictPolicy policy = useCase.conflict();
    for (int attempt = 1; ; attempt++) {
      Attempt<E> outcome = attempt(useCase, context, command);
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
      UseCase<C, E> useCase, Context context, C command) {
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
    return persist(useCase, context, command, plan);
  }

  private <C, E extends DomainEvent> Attempt<E> persist(
      UseCase<C, E> useCase, Context context, C command, Plan<E> plan) {
    String step = "BEGIN";
    try (Connection connection = database.dataSource().getConnection()) {
      boolean committed = false;
      try {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        Tx tx = new Tx(connection);

        step = "PERSIST";
        for (Plan.Step planned : plan.steps()) {
          if (!apply(planned, tx)) {
            return new Conflict<>();
          }
        }

        step = "OUTBOX";
        List<DomainEvent> events = new ArrayList<>(plan.steps().size());
        for (Plan.Step planned : plan.steps()) {
          events.add(planned.event());
        }
        sink.writeEvents(tx, context, events);

        step = "AUDIT";
        sink.writeAudit(tx, auditEntry(useCase.name(), context, command, plan));

        step = "COMMIT";
        connection.commit();
        committed = true;
        return done(new UseCaseResult.Committed<>(plan.result()));
      } finally {
        if (!committed) {
          rollbackQuietly(connection);
        }
      }
    } catch (Exception e) {
      return failure(useCase, step, e);
    }
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
