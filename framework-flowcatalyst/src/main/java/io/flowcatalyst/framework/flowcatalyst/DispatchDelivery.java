package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.database.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One dispatch job as delivered by the platform to {@code POST {prefix}/dispatch}, after its
 * signature was checked.
 *
 * <p>The body is either the platform's envelope ({@code {id, type, attemptNumber, source?,
 * subject?, correlationId?, messageGroup?, clientId?, clientCode?, data}}) or, for a subscription
 * with {@code dataOnly}, the raw payload. The job id and type always come from the
 * {@code X-Dispatch-Job-Id} and {@code X-Event-Type} headers; the body is an envelope when it is a
 * JSON object whose {@code id} and {@code type} equal them.
 */
public final class DispatchDelivery {

  /** Names accepted by {@link #once}. */
  private static final Pattern HANDLER_NAME = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

  private static final String INSERT_ONCE =
      "INSERT INTO fw_dispatch_once (handler, job_id) VALUES (?, ?) ON CONFLICT DO NOTHING";

  /**
   * The client a job is for, from {@code X-FlowCatalyst-Client: {id}:{code}}.
   *
   * @param id the platform's client id
   * @param code the client's identifier; the {@code Context} carries it as the client
   */
  public record Client(String id, ClientId code) {}

  private final String jobId;
  private final String eventType;
  private final @Nullable Integer attemptNumber;
  private final @Nullable String source;
  private final @Nullable String subject;
  private final @Nullable String messageGroup;
  private final CorrelationId correlationId;
  private final @Nullable Client client;
  private final boolean dataOnly;
  private final @Nullable JsonNode data;
  private final byte[] rawBody;

  DispatchDelivery(
      String jobId,
      String eventType,
      @Nullable Integer attemptNumber,
      @Nullable String source,
      @Nullable String subject,
      @Nullable String messageGroup,
      CorrelationId correlationId,
      @Nullable Client client,
      boolean dataOnly,
      @Nullable JsonNode data,
      byte[] rawBody) {
    this.jobId = jobId;
    this.eventType = eventType;
    this.attemptNumber = attemptNumber;
    this.source = source;
    this.subject = subject;
    this.messageGroup = messageGroup;
    this.correlationId = correlationId;
    this.client = client;
    this.dataOnly = dataOnly;
    this.data = data;
    this.rawBody = rawBody.clone();
  }

  /**
   * The dispatch job's id: the idempotency key for this delivery.
   *
   * @return the id from {@code X-Dispatch-Job-Id}
   */
  public String jobId() {
    return jobId;
  }

  /**
   * The job's code, normally the event type.
   *
   * @return the value of {@code X-Event-Type}
   */
  public String eventType() {
    return eventType;
  }

  /**
   * Which delivery attempt this is, starting at 1.
   *
   * @return the envelope's {@code attemptNumber}; empty for a data-only delivery
   */
  public OptionalInt attemptNumber() {
    Integer n = attemptNumber;
    return n == null ? OptionalInt.empty() : OptionalInt.of(n);
  }

  /**
   * The event's source.
   *
   * @return the envelope's {@code source}, if any
   */
  public Optional<String> source() {
    return Optional.ofNullable(source);
  }

  /**
   * The event's subject.
   *
   * @return the envelope's {@code subject}, if any
   */
  public Optional<String> subject() {
    return Optional.ofNullable(subject);
  }

  /**
   * The message group the platform orders this job in.
   *
   * @return the envelope's {@code messageGroup}, if any
   */
  public Optional<String> messageGroup() {
    return Optional.ofNullable(messageGroup);
  }

  /**
   * The correlation id, the same as the handler's {@code Context}.
   *
   * @return the envelope's {@code correlationId}, or a generated one
   */
  public CorrelationId correlationId() {
    return correlationId;
  }

  /**
   * The client the job is for.
   *
   * @return from {@code X-FlowCatalyst-Client}; empty for a platform-scoped job
   */
  public Optional<Client> client() {
    return Optional.ofNullable(client);
  }

  /**
   * Whether the body is the raw payload rather than the envelope (a {@code dataOnly}
   * subscription).
   *
   * @return true for a data-only delivery
   */
  public boolean isDataOnly() {
    return dataOnly;
  }

  /**
   * The payload as JSON.
   *
   * @return the envelope's {@code data}; for a data-only delivery the whole body parsed as JSON;
   *     empty when absent or, for data-only, not JSON
   */
  public Optional<JsonNode> data() {
    return Optional.ofNullable(data);
  }

  /**
   * The body exactly as received and verified.
   *
   * @return a copy of the bytes
   */
  public byte[] rawBody() {
    return rawBody.clone();
  }

  /**
   * Runs work at most once for this job and a handler name, in one transaction with a record of
   * {@code (handlerName, jobId)} in the {@code fw_dispatch_once} table (the module's migration).
   *
   * <p>Inserts the record first: when it already exists the transaction is rolled back and the
   * work does not run ({@link OnceResult.AlreadyDone}). Otherwise the work runs and both commit
   * together ({@link OnceResult.Ran}). A concurrent second delivery of the same job waits on the
   * first's uncommitted record, then skips (first committed) or runs (first rolled back). If the
   * work throws, everything is rolled back and the exception propagates, so the platform retries.
   *
   * @param <T> what the work returns
   * @param db the database holding {@code fw_dispatch_once} (migrations create it on the default
   *     instance) and the work's tables
   * @param handlerName a stable name for this piece of work, {@code [A-Za-z0-9._:-]{1,200}}; one
   *     job may be handled once per name
   * @param work the work
   * @return whether it ran
   * @throws IllegalArgumentException when the handler name is malformed
   * @throws Exception what the work threw, or an {@link SQLException}
   */
  public <T extends @Nullable Object> OnceResult<T> once(
      Database db, String handlerName, OnceWork<T> work) throws Exception {
    if (!HANDLER_NAME.matcher(handlerName).matches()) {
      throw new IllegalArgumentException("a once handler name must match [A-Za-z0-9._:-]{1,200}");
    }
    try (Connection c = db.dataSource().getConnection()) {
      boolean autoCommit = c.getAutoCommit();
      int isolation = c.getTransactionIsolation();
      c.setAutoCommit(false);
      c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      try {
        int inserted;
        try (PreparedStatement s = c.prepareStatement(INSERT_ONCE)) {
          s.setString(1, handlerName);
          s.setString(2, jobId);
          inserted = s.executeUpdate();
        }
        if (inserted == 0) {
          c.rollback();
          return new OnceResult.AlreadyDone<>();
        }
        T value = work.run(c);
        c.commit();
        return new OnceResult.Ran<>(value);
      } catch (Throwable t) {
        rollbackQuietly(c, t);
        throw t;
      } finally {
        restore(c, autoCommit, isolation);
      }
    }
  }

  private static void rollbackQuietly(Connection c, Throwable cause) {
    try {
      c.rollback();
    } catch (SQLException | RuntimeException e) {
      cause.addSuppressed(e);
    }
  }

  private static void restore(Connection c, boolean autoCommit, int isolation) {
    try {
      c.setTransactionIsolation(isolation);
      c.setAutoCommit(autoCommit);
    } catch (SQLException e) {
      // The pool resets connection state on return; nothing else to do.
    }
  }

  @Override
  public String toString() {
    return "DispatchDelivery[jobId=" + jobId + ", eventType=" + eventType + "]";
  }
}
