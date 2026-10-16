package io.flowcatalyst.framework.flowcatalyst;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the admin pages read from, and send to, the platform for users, invitations, events and
 * dispatch jobs (M4e slices 1 and 4): plain values, no SDK types. Times are the platform's
 * ISO-8601 text, kept as it sends them. None of these ever holds a password hash, a token or a
 * second-factor field: the platform does not send them, and the parsing reads named fields only.
 */
public final class PlatformAdminData {
  private PlatformAdminData() {}

  /**
   * One of the application's users.
   *
   * @param id the user's platform id
   * @param email the email address
   * @param name the display name
   * @param active whether the user can sign in
   * @param clientId the client the user is homed at
   * @param idpType {@code INTERNAL} (a platform password) or {@code OIDC} (federated)
   * @param roles the application's own roles the user holds
   * @param invitation {@code PENDING} or {@code EXPIRED} while the user awaits an invitation, else null
   * @param lastLoginAt the last sign-in, or null
   * @param createdAt when the user was created
   */
  public record UserInfo(
      String id,
      String email,
      String name,
      boolean active,
      String clientId,
      String idpType,
      List<String> roles,
      @Nullable String invitation,
      @Nullable String lastLoginAt,
      String createdAt) {}

  /**
   * The filters and the page of a user list.
   *
   * @param q a substring of name or email, or null
   * @param active only active (true) or inactive (false) users, or null for both
   * @param clientId only users homed at this client, or null
   * @param roles the application's roles; users holding any (empty for no filter)
   * @param page the 0-based page
   * @param pageSize the page size (1 to 200)
   * @param sortField {@code name}, {@code email} or {@code createdAt}, or null
   * @param sortOrder {@code asc} or {@code desc}, or null
   */
  public record UserQuery(
      @Nullable String q,
      @Nullable Boolean active,
      @Nullable String clientId,
      List<String> roles,
      int page,
      int pageSize,
      @Nullable String sortField,
      @Nullable String sortOrder) {}

  /**
   * A page of users.
   *
   * @param users the users
   * @param total how many match in all
   * @param page the 0-based page
   * @param pageSize the page size the platform used
   */
  public record UserPage(List<UserInfo> users, int total, int page, int pageSize) {}

  /**
   * An outstanding invitation. Never carries the link.
   *
   * @param userId the invited user's id (the id the resend and revoke calls take)
   * @param email the email address
   * @param name the display name
   * @param clientId the client the user is homed at
   * @param roles the application's roles the user holds
   * @param status {@code PENDING} (a live link exists) or {@code EXPIRED}
   * @param createdAt when the user was created
   * @param expiresAt when the live link lapses, or null
   */
  public record InvitationInfo(
      String userId,
      String email,
      String name,
      String clientId,
      List<String> roles,
      String status,
      String createdAt,
      @Nullable String expiresAt) {}

  /**
   * A page of invitations.
   *
   * @param invitations the invitations
   * @param total how many match in all
   * @param page the 0-based page
   * @param pageSize the page size the platform used
   */
  public record InvitationPage(List<InvitationInfo> invitations, int total, int page, int pageSize) {}

  /**
   * An invitation to send.
   *
   * @param email the invitee's email
   * @param name the invitee's name
   * @param clientId the client (id or identifier) the user is homed at
   * @param roles the application's roles to give (at least one)
   * @param sendInvitation whether the platform emails the invitation
   * @param returnInviteLink whether the platform returns the link instead of emailing it
   */
  public record InviteRequest(
      String email, String name, String clientId, List<String> roles, boolean sendInvitation, boolean returnInviteLink) {}

  /**
   * The platform's answer to an invitation. {@code inviteLink} is a bearer credential: it is
   * handed to the person who asked for it and to nobody else, and is in no log.
   *
   * @param id the new user's id
   * @param inviteLink the link when it was asked for, else null
   * @param invitationSent whether the platform emailed the invitation
   */
  public record InviteResult(String id, @Nullable String inviteLink, boolean invitationSent) {
    @Override
    public String toString() {
      return "InviteResult[id=" + id + ", invitationSent=" + invitationSent + ", inviteLink=" + (inviteLink == null ? "none" : "withheld") + "]";
    }
  }

  /**
   * The platform's answer to a resend. {@code inviteLink} is a bearer credential, as above.
   *
   * @param inviteLink the link when it was asked for, else null
   * @param expiresAt when the fresh link lapses, or null
   */
  public record ResendResult(@Nullable String inviteLink, @Nullable String expiresAt) {
    @Override
    public String toString() {
      return "ResendResult[expiresAt=" + expiresAt + ", inviteLink=" + (inviteLink == null ? "none" : "withheld") + "]";
    }
  }

  /**
   * An event in a list.
   *
   * @param id the event id
   * @param type the event type, {@code {application}:{subdomain}:{aggregate}:{event}}
   * @param source the source
   * @param subject the subject, or null
   * @param time when it happened
   * @param application the application facet, or null
   * @param messageGroup the message group, or null
   * @param correlationId the correlation id, or null
   * @param clientId the client, or null for a platform-wide event
   */
  public record EventInfo(
      String id,
      String type,
      String source,
      @Nullable String subject,
      String time,
      @Nullable String application,
      @Nullable String messageGroup,
      @Nullable String correlationId,
      @Nullable String clientId) {}

  /**
   * One key and value of an event's context data.
   *
   * @param key the key
   * @param value the value
   */
  public record ContextEntry(String key, String value) {}

  /**
   * An event with its payload.
   *
   * @param event the list fields
   * @param data the payload as JSON text, or null
   * @param context the context data
   * @param causationId what caused it, or null
   */
  public record EventDetail(EventInfo event, @Nullable String data, List<ContextEntry> context, @Nullable String causationId) {}

  /**
   * The filters and the page of an event list.
   *
   * @param type an exact event type, or null
   * @param subject a subject, or null
   * @param correlationId a correlation id, or null
   * @param clientId a client id, or null
   * @param since an RFC 3339 time, or null
   * @param until an RFC 3339 time, or null
   * @param limit how many rows
   * @param offset how many rows to skip
   */
  public record EventQuery(
      @Nullable String type,
      @Nullable String subject,
      @Nullable String correlationId,
      @Nullable String clientId,
      @Nullable String since,
      @Nullable String until,
      int limit,
      int offset) {}

  /**
   * A dispatch job. The list fills the first part; the single read adds the rest (null in a list).
   *
   * @param id the job id
   * @param eventId the event that raised it, or null
   * @param subscriptionId the subscription that raised it, or null
   * @param clientId the client, or null
   * @param code the job's code (an event type for a fanned-out job)
   * @param status its state
   * @param kind {@code EVENT} or {@code TASK}
   * @param targetUrl where the platform delivers it
   * @param mode the dispatch mode
   * @param messageGroup the message group, or null
   * @param descriptor what the job is in words, or null
   * @param createdAt when it was created
   * @param lastAttemptAt when it was last tried, or null
   * @param completedAt when it finished, or null
   * @param attemptCount how many attempts so far
   * @param protocol the delivery protocol, or null in a list
   * @param timeoutSeconds the delivery timeout, or null in a list
   * @param maxRetries the retry limit, or null in a list
   * @param retryStrategy the retry strategy, or null in a list
   * @param lastError the last error, or null
   */
  public record JobInfo(
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
      int attemptCount,
      @Nullable String protocol,
      @Nullable Integer timeoutSeconds,
      @Nullable Integer maxRetries,
      @Nullable String retryStrategy,
      @Nullable String lastError) {}

  /**
   * The filters and the page of a dispatch-job list.
   *
   * @param statuses the states to keep (empty for any)
   * @param code an exact code, or null
   * @param clientId a client id, or null
   * @param messageGroup an exact message group, or null
   * @param since an RFC 3339 time, or null
   * @param until an RFC 3339 time, or null
   * @param limit how many rows
   * @param offset how many rows to skip
   */
  public record JobQuery(
      List<String> statuses,
      @Nullable String code,
      @Nullable String clientId,
      @Nullable String messageGroup,
      @Nullable String since,
      @Nullable String until,
      int limit,
      int offset) {}

  /**
   * One delivery attempt of a dispatch job.
   *
   * @param attemptNumber 1 for the first
   * @param attemptedAt when it started
   * @param completedAt when it finished, or null
   * @param durationMillis how long it took, or null
   * @param responseCode the subscriber's HTTP status, or null
   * @param responseBody what the subscriber answered (the platform's text, unbounded), or null
   * @param success whether the delivery counted as delivered
   * @param errorMessage why it failed, or null
   * @param errorType {@code CONNECTION}, {@code TIMEOUT}, {@code HTTP_ERROR}, {@code VALIDATION}
   *     or {@code UNKNOWN}, or null
   */
  public record AttemptInfo(
      int attemptNumber,
      String attemptedAt,
      @Nullable String completedAt,
      @Nullable Long durationMillis,
      @Nullable Integer responseCode,
      @Nullable String responseBody,
      boolean success,
      @Nullable String errorMessage,
      @Nullable String errorType) {}
}
