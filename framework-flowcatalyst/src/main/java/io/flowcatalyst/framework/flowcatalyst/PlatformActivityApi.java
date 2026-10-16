package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.AdminApi.Page;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.TypeRef;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire types and operations of the activity section (admin contract section 12): the
 * application's own events and the dispatch jobs that deliver them, read-only.
 */
public final class PlatformActivityApi {
  private PlatformActivityApi() {}

  /**
   * An event in a list.
   *
   * @param id the event id
   * @param type the event type, {@code {application}:{subdomain}:{aggregate}:{event}}
   * @param source the source
   * @param subject the subject, absent when none
   * @param time when it happened
   * @param messageGroup the message group, absent when none
   * @param correlationId the correlation id, absent when none
   * @param clientId the client, absent for a platform-wide event
   */
  public record AdminEvent(
      String id,
      String type,
      String source,
      @Nullable String subject,
      String time,
      @Nullable String messageGroup,
      @Nullable String correlationId,
      @Nullable String clientId) {}

  /**
   * One key and value of an event's context data.
   *
   * @param key the key
   * @param value the value
   */
  public record AdminContextEntry(String key, String value) {}

  /**
   * A dispatch job: the delivery of an event (or a task) to a subscriber's endpoint. The target
   * may belong to another application's subscription: the job is this application's because its
   * code is this application's event type.
   *
   * @param id the job id
   * @param eventId the event that raised it, absent for a task
   * @param subscriptionId the subscription that raised it, absent when none
   * @param clientId the client, absent when platform-wide
   * @param code the job's code (the event type for a fanned-out job)
   * @param status its state
   * @param kind {@code EVENT} or {@code TASK}
   * @param targetUrl where the platform delivers it, without user info or query
   * @param mode the dispatch mode
   * @param messageGroup the message group, absent when none
   * @param descriptor what the job is in words (the raising subscription's name), absent when none
   * @param createdAt when it was created
   * @param lastAttemptAt when it was last tried, absent when never
   * @param completedAt when it finished, absent when it has not
   * @param attemptCount how many attempts so far
   */
  public record AdminDispatchJob(
      String id,
      @Nullable String eventId,
      @Nullable String subscriptionId,
      @Nullable String clientId,
      String code,
      String status,
      String kind,
      String targetUrl,
      String mode,
      @Nullable String messageGroup,
      @Nullable String descriptor,
      String createdAt,
      @Nullable String lastAttemptAt,
      @Nullable String completedAt,
      int attemptCount) {}

  /**
   * An event with its payload and the jobs that deliver it.
   *
   * @param event the list fields
   * @param data the payload as JSON text, cut at 20 000 characters; absent when there is none
   * @param dataTruncated whether {@code data} was cut
   * @param context the context data
   * @param causationId what caused it, absent when unknown
   * @param dispatchJobs this application's jobs for the event, newest first as the platform sends them
   */
  public record AdminEventDetail(
      AdminEvent event,
      @Nullable String data,
      boolean dataTruncated,
      List<AdminContextEntry> context,
      @Nullable String causationId,
      List<AdminDispatchJob> dispatchJobs) {}

  /**
   * One delivery attempt.
   *
   * @param attemptNumber 1 for the first
   * @param attemptedAt when it started
   * @param completedAt when it finished, absent when not known
   * @param durationMillis how long it took
   * @param responseCode the subscriber's HTTP status
   * @param responseBody what the subscriber answered, cut at 4 000 characters
   * @param responseBodyTruncated whether {@code responseBody} was cut
   * @param success whether the delivery counted as delivered
   * @param errorMessage why it failed
   * @param errorType {@code CONNECTION}, {@code TIMEOUT}, {@code HTTP_ERROR}, {@code VALIDATION} or {@code UNKNOWN}
   */
  public record AdminAttempt(
      int attemptNumber,
      String attemptedAt,
      @Nullable String completedAt,
      @Nullable Long durationMillis,
      @Nullable Integer responseCode,
      @Nullable String responseBody,
      boolean responseBodyTruncated,
      boolean success,
      @Nullable String errorMessage,
      @Nullable String errorType) {}

  /**
   * A dispatch job with its delivery attempts.
   *
   * @param job the list fields
   * @param protocol the delivery protocol
   * @param timeoutSeconds the delivery timeout
   * @param maxRetries the retry limit
   * @param retryStrategy the retry strategy
   * @param lastError the last error
   * @param attempts the attempts, in the order the platform sends them
   */
  public record AdminDispatchJobDetail(
      AdminDispatchJob job,
      @Nullable String protocol,
      @Nullable Integer timeoutSeconds,
      @Nullable Integer maxRetries,
      @Nullable String retryStrategy,
      @Nullable String lastError,
      List<AdminAttempt> attempts) {}

  static final QueryParam<@Nullable String> TYPE = QueryParam.string("type").optional();
  static final QueryParam<@Nullable String> SUBJECT = QueryParam.string("subject").optional();
  static final QueryParam<@Nullable String> CORRELATION = QueryParam.string("correlationId").optional();
  static final QueryParam<@Nullable String> CLIENT = QueryParam.string("clientId").optional();
  static final QueryParam<@Nullable String> SINCE = QueryParam.string("since").optional();
  static final QueryParam<@Nullable String> UNTIL = QueryParam.string("until").optional();
  static final QueryParam<@Nullable String> STATUS = QueryParam.string("status").optional();
  static final QueryParam<@Nullable String> CODE = QueryParam.string("code").optional();
  static final QueryParam<@Nullable String> MESSAGE_GROUP = QueryParam.string("messageGroup").optional();
  static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(50);
  static final QueryParam<@Nullable String> AFTER = QueryParam.string("after").optional();
  static final PathParam<String> ID = PathParam.string("id");

  /** The operations for one application. */
  static final class Operations {
    final Operation<NoBody, Page<AdminEvent>> listEvents;
    final Operation<NoBody, AdminEventDetail> getEvent;
    final Operation<NoBody, Page<AdminDispatchJob>> listJobs;
    final Operation<NoBody, AdminDispatchJobDetail> getJob;

    Operations(String view) {
      listEvents =
          Operation.get("/api/admin/events", new TypeRef<Page<AdminEvent>>() {})
              .id("listAdminEvents")
              .summary("The application's own events, newest first")
              .tag("admin")
              .query(TYPE, SUBJECT, CORRELATION, CLIENT, SINCE, UNTIL, LIMIT, AFTER)
              .permission(view)
              .error(400, "INVALID_CURSOR", "the cursor is not one this server gave")
              .error(400, "INVALID_TIMESTAMP", "since and until are RFC 3339 times")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      getEvent =
          Operation.get("/api/admin/events/{id}", AdminEventDetail.class)
              .id("getAdminEvent")
              .summary("One event with its payload and the dispatch jobs that deliver it")
              .tag("admin")
              .path(ID)
              .permission(view)
              .error(404, "EVENT_NOT_FOUND", "there is no such event of this application")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      listJobs =
          Operation.get("/api/admin/dispatch-jobs", new TypeRef<Page<AdminDispatchJob>>() {})
              .id("listAdminDispatchJobs")
              .summary("The dispatch jobs of the application's own events, newest first")
              .tag("admin")
              .query(STATUS, CODE, CLIENT, MESSAGE_GROUP, SINCE, UNTIL, LIMIT, AFTER)
              .permission(view)
              .error(400, "INVALID_CURSOR", "the cursor is not one this server gave")
              .error(400, "INVALID_TIMESTAMP", "since and until are RFC 3339 times")
              .error(400, "INVALID_STATUS", "the status is an upper-case word")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      getJob =
          Operation.get("/api/admin/dispatch-jobs/{id}", AdminDispatchJobDetail.class)
              .id("getAdminDispatchJob")
              .summary("One dispatch job with its delivery attempts")
              .tag("admin")
              .path(ID)
              .permission(view)
              .error(404, "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job of this application")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
    }
  }
}
