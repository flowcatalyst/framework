package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * One firing of a scheduled job, from the platform's envelope ({@code {jobId, jobCode, instanceId,
 * scheduledFor?, firedAt, triggerKind, correlationId?, payload?, tracksCompletion,
 * timeoutSeconds?, concurrent}}), validated before any handler sees it.
 */
public final class ScheduledJobFiring {

  private static final Logger LOG = LoggerFactory.getLogger(ScheduledJobFiring.class);

  /** The longest line {@link #log} sends; longer ones are cut. */
  public static final int MAX_LOG_LENGTH = 10_000;

  /** The level of a line appended to the instance's log on the platform. */
  public enum LogLevel {
    /** Detail. */
    DEBUG,
    /** Progress. */
    INFO,
    /** Something to look at. */
    WARN,
    /** A failure. */
    ERROR
  }

  private final String jobId;
  private final String jobCode;
  private final String instanceId;
  private final @Nullable Instant scheduledFor;
  private final Instant firedAt;
  private final TriggerKind triggerKind;
  private final CorrelationId correlationId;
  private final @Nullable JsonNode payload;
  private final boolean tracksCompletion;
  private final @Nullable Duration timeout;
  private final boolean concurrent;
  private final @Nullable PlatformClient platform;
  private volatile @Nullable Long fencingToken;

  ScheduledJobFiring(
      String jobId,
      String jobCode,
      String instanceId,
      @Nullable Instant scheduledFor,
      Instant firedAt,
      TriggerKind triggerKind,
      CorrelationId correlationId,
      @Nullable JsonNode payload,
      boolean tracksCompletion,
      @Nullable Duration timeout,
      boolean concurrent,
      @Nullable PlatformClient platform) {
    this.jobId = jobId;
    this.jobCode = jobCode;
    this.instanceId = instanceId;
    this.scheduledFor = scheduledFor;
    this.firedAt = firedAt;
    this.triggerKind = triggerKind;
    this.correlationId = correlationId;
    this.payload = payload;
    this.tracksCompletion = tracksCompletion;
    this.timeout = timeout;
    this.concurrent = concurrent;
    this.platform = platform;
  }

  /**
   * The scheduled job's id on the platform.
   *
   * @return the id
   */
  public String jobId() {
    return jobId;
  }

  /**
   * The job's code, which selected the handler.
   *
   * @return the code
   */
  public String jobCode() {
    return jobCode;
  }

  /**
   * This firing's instance id: the execution id in the handler's {@code Context}.
   *
   * @return the id
   */
  public String instanceId() {
    return instanceId;
  }

  /**
   * The cron slot this firing is for.
   *
   * @return the slot; empty for a manual firing
   */
  public Optional<Instant> scheduledFor() {
    return Optional.ofNullable(scheduledFor);
  }

  /**
   * When the platform fired it.
   *
   * @return the time
   */
  public Instant firedAt() {
    return firedAt;
  }

  /**
   * Why it fired.
   *
   * @return cron or manual
   */
  public TriggerKind triggerKind() {
    return triggerKind;
  }

  /**
   * The correlation id, the same as the handler's {@code Context}.
   *
   * @return the envelope's, or a generated one
   */
  public CorrelationId correlationId() {
    return correlationId;
  }

  /**
   * The job's payload.
   *
   * @return the payload; empty when the job has none
   */
  public Optional<JsonNode> payload() {
    return Optional.ofNullable(payload);
  }

  /**
   * Whether the platform waits for a completion report.
   *
   * @return true when the job definition tracks completion
   */
  public boolean tracksCompletion() {
    return tracksCompletion;
  }

  /**
   * The timeout from the envelope.
   *
   * @return the job's {@code timeoutSeconds}; empty when the job has none (then
   *     {@code flowcatalyst.receivers.job-timeout} applies)
   */
  public Optional<Duration> timeout() {
    return Optional.ofNullable(timeout);
  }

  /**
   * Whether the job definition allows overlapping runs; when false the handler runs under the
   * overlap lock.
   *
   * @return the envelope's {@code concurrent}
   */
  public boolean concurrent() {
    return concurrent;
  }

  /**
   * The fencing token of the overlap lock this run holds. Pass it to what the job writes, so a
   * run that lost its lock can be told from the current one.
   *
   * @return the token; empty for a concurrent job
   */
  public OptionalLong fencingToken() {
    Long token = fencingToken;
    return token == null ? OptionalLong.empty() : OptionalLong.of(token);
  }

  void fencingToken(long token) {
    this.fencingToken = token;
  }

  /**
   * Appends an INFO line to this instance's log on the platform. Best effort: a failure is logged
   * here and never thrown.
   *
   * @param message the line
   */
  public void log(String message) {
    log(LogLevel.INFO, message);
  }

  /**
   * Appends a line to this instance's log on the platform ({@code POST
   * /api/scheduled-jobs/instances/{id}/log}). Best effort: a failure is logged here and never
   * thrown. Lines longer than {@value #MAX_LOG_LENGTH} characters are cut.
   *
   * @param level the level
   * @param message the line
   */
  public void log(LogLevel level, String message) {
    PlatformClient client = platform;
    if (client == null) {
      return;
    }
    String line = message.length() > MAX_LOG_LENGTH ? message.substring(0, MAX_LOG_LENGTH) : message;
    try {
      client.logJobInstance(instanceId, level, line);
    } catch (RuntimeException e) {
      LOG.warn(
          "flowcatalyst: could not append to the log of scheduled job {} instance {}: {}",
          jobCode,
          instanceId,
          e.getClass().getSimpleName());
    }
  }

  @Override
  public String toString() {
    return "ScheduledJobFiring[jobCode=" + jobCode + ", instanceId=" + instanceId + "]";
  }
}
