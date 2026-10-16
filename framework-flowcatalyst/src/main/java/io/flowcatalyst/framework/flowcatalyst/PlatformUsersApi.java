package io.flowcatalyst.framework.flowcatalyst;

import static io.flowcatalyst.framework.http.Rules.each;
import static io.flowcatalyst.framework.http.Rules.maxLength;
import static io.flowcatalyst.framework.http.Rules.notBlank;
import static io.flowcatalyst.framework.http.Rules.pattern;
import static io.flowcatalyst.framework.http.Rules.size;

import io.flowcatalyst.framework.admin.AdminApi.Page;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.TypeRef;
import io.flowcatalyst.framework.http.Validator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire types and operations of the users section (admin contract section 12): the application's
 * users and invitations over the platform's application-user routes.
 */
public final class PlatformUsersApi {
  private PlatformUsersApi() {}

  /** Whether an invitation can still be used. */
  public enum InvitationStatus {
    /** A live link exists. */
    PENDING,
    /** The link lapsed: resend it. */
    EXPIRED
  }

  /**
   * One of the application's users.
   *
   * @param id the user's id
   * @param email the email address
   * @param name the display name
   * @param active whether the user can sign in
   * @param clientId the client the user is homed at
   * @param idpType {@code INTERNAL} or {@code OIDC}
   * @param roles the application's roles the user holds
   * @param invitation {@code PENDING} or {@code EXPIRED} while the user awaits an invitation, absent otherwise
   * @param lastLoginAt the last sign-in, absent when never
   * @param createdAt when the user was created
   */
  public record AdminUser(
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
   * An outstanding invitation. There is no link here: it is shown once, to the person who asked.
   *
   * @param userId the invited user's id (what resend and revoke take)
   * @param email the email address
   * @param name the display name
   * @param clientId the client the user is homed at
   * @param roles the application's roles the user holds
   * @param status pending or expired
   * @param createdAt when the user was created
   * @param expiresAt when the live link lapses, absent when expired
   */
  public record AdminInvitation(
      String userId,
      String email,
      String name,
      String clientId,
      List<String> roles,
      InvitationStatus status,
      String createdAt,
      @Nullable String expiresAt) {}

  /**
   * An invitation to send.
   *
   * @param email the invitee's email address
   * @param name the invitee's name
   * @param clientId the client the user is homed at: an id or the client's identifier
   * @param roles the application's roles to give, at least one
   * @param sendInvitation whether the platform emails the invitation (default true)
   * @param returnInviteLink whether the answer carries the link, shown once, instead of an email (default false)
   */
  public record InviteBody(
      String email, String name, String clientId, List<String> roles, @Nullable Boolean sendInvitation, @Nullable Boolean returnInviteLink) {
    static final Validator<InviteBody> V =
        Validator.of(InviteBody.class)
            .field("email", InviteBody::email, notBlank(), maxLength(254), pattern("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
            .field("name", InviteBody::name, notBlank(), maxLength(200))
            .field("clientId", InviteBody::clientId, notBlank(), maxLength(100))
            .field("roles", InviteBody::roles, size(1, 50), each(notBlank()), each(maxLength(100)));
  }

  /**
   * The answer to an invitation. {@code inviteLink} is a credential: it is in this answer only when
   * asked for, the answer is not cached, and it is shown once and not stored or logged.
   *
   * @param id the new user's id
   * @param invitationSent whether the platform emailed the invitation
   * @param inviteLink the link, only when asked for
   */
  public record InviteResult(String id, boolean invitationSent, @Nullable String inviteLink) {
    @Override
    public String toString() {
      return "InviteResult[id=" + id + ", invitationSent=" + invitationSent + ", inviteLink=" + (inviteLink == null ? "none" : "withheld") + "]";
    }
  }

  /**
   * A resend.
   *
   * @param returnInviteLink whether the answer carries the fresh link, shown once, instead of an email (default false)
   */
  public record ResendBody(@Nullable Boolean returnInviteLink) {
    static final Validator<ResendBody> V = Validator.of(ResendBody.class);
  }

  /**
   * The answer to a resend. {@code inviteLink} is a credential, as in {@link InviteResult}.
   *
   * @param expiresAt when the fresh link lapses, absent when not known
   * @param inviteLink the link, only when asked for
   */
  public record ResendResult(@Nullable String expiresAt, @Nullable String inviteLink) {
    @Override
    public String toString() {
      return "ResendResult[expiresAt=" + expiresAt + ", inviteLink=" + (inviteLink == null ? "none" : "withheld") + "]";
    }
  }

  static final QueryParam<@Nullable String> Q = QueryParam.string("q").optional();
  static final QueryParam<@Nullable Boolean> ACTIVE = QueryParam.bool("active").optional();
  static final QueryParam<@Nullable String> CLIENT = QueryParam.string("clientId").optional();
  static final QueryParam<@Nullable String> ROLES = QueryParam.string("roles").optional();
  static final QueryParam<@Nullable InvitationStatus> STATUS = QueryParam.enumOf("status", InvitationStatus.class).optional();
  static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(50);
  static final QueryParam<@Nullable String> AFTER = QueryParam.string("after").optional();
  static final PathParam<String> ID = PathParam.string("id");

  /** The operations for one application. */
  static final class Operations {
    final Operation<NoBody, Page<AdminUser>> listUsers;
    final Operation<NoBody, AdminUser> getUser;
    final Operation<InviteBody, InviteResult> invite;
    final Operation<NoBody, Page<AdminInvitation>> listInvitations;
    final Operation<ResendBody, ResendResult> resend;
    final Operation<NoBody, NoBody> revoke;

    Operations(String view, String invite, String resend, String revoke) {
      listUsers =
          Operation.get("/api/admin/users", new TypeRef<Page<AdminUser>>() {})
              .id("listAdminUsers")
              .summary("The users who hold this application's roles")
              .tag("admin")
              .query(Q, ACTIVE, CLIENT, ROLES, LIMIT, AFTER)
              .permission(view)
              .error(400, "INVALID_CURSOR", "the cursor is not one this server gave")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      getUser =
          Operation.get("/api/admin/users/{id}", AdminUser.class)
              .id("getAdminUser")
              .summary("One user of this application")
              .tag("admin")
              .path(ID)
              .permission(view)
              .error(404, "USER_NOT_FOUND", "there is no such user of this application")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      this.invite =
          Operation.post("/api/admin/users", InviteBody.V, InviteResult.class)
              .id("inviteAdminUser")
              .summary("Invite a user into this application with its roles")
              .tag("admin")
              .status(201)
              .permission(invite)
              .error(400, "ROLES_REQUIRED", "give at least one of the application's roles")
              .error(400, "UNKNOWN_ROLE", "a role is not a role of this application")
              .error(403, "ROLE_APP_FORBIDDEN", "a role belongs to another application")
              .error(403, "CLIENT_NOT_AVAILABLE", "the client is not available to this application")
              .error(409, "EMAIL_EXISTS", "a user with this email already exists")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      listInvitations =
          Operation.get("/api/admin/invitations", new TypeRef<Page<AdminInvitation>>() {})
              .id("listAdminInvitations")
              .summary("The invitations that have not been accepted")
              .tag("admin")
              .query(STATUS, LIMIT, AFTER)
              .permission(view)
              .error(400, "INVALID_CURSOR", "the cursor is not one this server gave")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      this.resend =
          Operation.post("/api/admin/invitations/{id}/resend", ResendBody.V, ResendResult.class)
              .id("resendAdminInvitation")
              .summary("Send an invitation again; the earlier link stops working")
              .tag("admin")
              .path(ID)
              .permission(resend)
              .error(404, "INVITATION_NOT_FOUND", "there is no such invitation of this application")
              .error(400, "INVITATION_DELIVERY_REQUIRED", "the invitation has nowhere to go")
              .error(502, "INVITE_DELIVERY_FAILED", "the invitation could not be delivered; try again")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
      this.revoke =
          Operation.delete("/api/admin/invitations/{id}", NoBody.class)
              .id("revokeAdminInvitation")
              .summary("Revoke an invitation: the user is deactivated and the links stop working")
              .tag("admin")
              .path(ID)
              .permission(revoke)
              .error(404, "INVITATION_NOT_FOUND", "there is no such invitation of this application")
              .error(502, "PLATFORM_ERROR", "the platform refused or could not be reached")
              .error(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
              .build();
    }
  }
}
