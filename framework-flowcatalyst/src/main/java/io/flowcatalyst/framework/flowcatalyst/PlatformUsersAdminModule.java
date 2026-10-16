package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminApi;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.InvitationInfo;
import io.flowcatalyst.framework.flowcatalyst.PlatformAdminData.UserInfo;
import io.flowcatalyst.framework.flowcatalyst.PlatformUsersApi.AdminInvitation;
import io.flowcatalyst.framework.flowcatalyst.PlatformUsersApi.AdminUser;
import io.flowcatalyst.framework.flowcatalyst.PlatformUsersApi.InvitationStatus;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The admin <b>users</b> section (M4e slice 1, admin contract section 12): the users who hold this
 * application's roles, their pending invitations, and inviting, resending and revoking, over the
 * platform's application-user routes, called as the application's own service account after the
 * framework has checked the signed-in user's permission. List it after {@code AdminModule} and
 * {@code FlowCatalystModule}.
 *
 * <p>Permissions: {@code {app}:admin:users:view} (users, invitations), {@code :invite}, {@code
 * :resend}, {@code :revoke}. The application code in every platform path is the configured one; ids
 * that could change a path are a {@code 404} without a call; roles of another application are refused
 * here too (the platform refuses them as well). An invitation link is a credential: it is in an answer
 * only when the caller asked for it, with {@code Cache-Control: no-store}, and in no log and no list.
 */
public final class PlatformUsersAdminModule implements Module {

  /** The module id. */
  public static final String ID = "admin-platform-users";

  private final ApplicationCode application;
  private final PlatformUsersApi.Operations operations;
  private final AdminSection users;

  private PlatformUsersAdminModule(ApplicationCode application) {
    this.application = application;
    this.operations =
        new PlatformUsersApi.Operations(view(application), invite(application), resend(application), revoke(application));
    this.users =
        AdminSection.of("users", "Users", "/admin/users", view(application))
            .icon("i-lucide-users")
            .description("The people who hold this application's roles, and their invitations.")
            .serving(operations.listUsers, operations.getUser, operations.invite, operations.listInvitations, operations.resend, operations.revoke);
  }

  /**
   * The module for an application.
   *
   * @param application the application code; it must equal {@code flowcatalyst.application}
   * @return the module
   */
  public static PlatformUsersAdminModule of(ApplicationCode application) {
    return new PlatformUsersAdminModule(application);
  }

  /**
   * The permission that opens the users section and reads users and invitations.
   *
   * @param application the application code
   * @return {@code {app}:admin:users:view}
   */
  public static String view(ApplicationCode application) {
    return application.value() + ":admin:users:view";
  }

  /**
   * The permission to invite a user.
   *
   * @param application the application code
   * @return {@code {app}:admin:users:invite}
   */
  public static String invite(ApplicationCode application) {
    return application.value() + ":admin:users:invite";
  }

  /**
   * The permission to resend an invitation.
   *
   * @param application the application code
   * @return {@code {app}:admin:users:resend}
   */
  public static String resend(ApplicationCode application) {
    return application.value() + ":admin:users:resend";
  }

  /**
   * The permission to revoke an invitation.
   *
   * @param application the application code
   * @return {@code {app}:admin:users:revoke}
   */
  public static String revoke(ApplicationCode application) {
    return application.value() + ":admin:users:revoke";
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, users);
  }

  @Override
  public void boot(App app) {
    PlatformClient client = app.get(PlatformClient.class);
    if (client.isEnabled() && !client.application().equals(application)) {
      throw new IllegalStateException(
          "PlatformUsersAdminModule is for application '"
              + application.value()
              + "' but flowcatalyst.application is '"
              + client.application().value()
              + "'");
    }
    Routes routes = app.get(Routes.class).forModule(ID);
    String own = application.value();

    routes.bind(
        operations.listUsers,
        r -> {
          int limit = AdminPlatform.limit(r.query(PlatformUsersApi.LIMIT));
          int page = AdminPlatform.cursor(r.query(PlatformUsersApi.AFTER));
          List<String> wanted = csv(r.query(PlatformUsersApi.ROLES));
          List<String> roles = ownRoles(own, wanted);
          if (!wanted.isEmpty() && roles.isEmpty()) {
            // Only the application's own roles can narrow its list; a foreign name matches nothing.
            return Outcome.ok(new AdminApi.Page<AdminUser>(List.of(), null));
          }
          PlatformAdminData.UserPage found =
              AdminPlatform.call(
                  client,
                  () ->
                      client.users(
                          new PlatformAdminData.UserQuery(
                              AdminPlatform.blankToNull(r.query(PlatformUsersApi.Q)),
                              r.query(PlatformUsersApi.ACTIVE),
                              AdminPlatform.blankToNull(r.query(PlatformUsersApi.CLIENT)),
                              roles,
                              page,
                              limit,
                              null,
                              null)),
                  null,
                  null);
          List<AdminUser> items = new ArrayList<>();
          for (UserInfo u : found.users()) {
            items.add(user(own, u));
          }
          boolean more = (long) (page + 1) * limit < found.total();
          return Outcome.ok(new AdminApi.Page<>(List.copyOf(items), more ? String.valueOf(page + 1) : null));
        });

    routes.bind(
        operations.getUser,
        r -> {
          String id = AdminPlatform.id(r.path(PlatformUsersApi.ID), "USER_NOT_FOUND", "there is no such user");
          UserInfo u = AdminPlatform.call(client, () -> client.user(id), "USER_NOT_FOUND", "there is no such user");
          return Outcome.ok(user(own, u));
        });

    routes.bind(
        operations.invite,
        r -> {
          PlatformUsersApi.InviteBody body = r.body();
          for (String role : body.roles()) {
            if (!ownsRole(own, role)) {
              throw new HttpException(403, "ROLE_APP_FORBIDDEN", "a role belongs to another application: roles must be this application's");
            }
          }
          boolean link = Boolean.TRUE.equals(body.returnInviteLink());
          boolean send = body.sendInvitation() == null || body.sendInvitation();
          PlatformAdminData.InviteResult made =
              AdminPlatform.write(
                  client,
                  () ->
                      client.invite(
                          new PlatformAdminData.InviteRequest(body.email().trim(), body.name().trim(), body.clientId().trim(), List.copyOf(body.roles()), send, link)),
                  null,
                  null);
          // The link goes to the person who asked for it, and only when they asked (whatever the platform sent).
          return Outcome.ok(new PlatformUsersApi.InviteResult(made.id(), made.invitationSent(), link ? made.inviteLink() : null))
              .header("Cache-Control", "no-store");
        });

    routes.bind(
        operations.listInvitations,
        r -> {
          int limit = AdminPlatform.limit(r.query(PlatformUsersApi.LIMIT));
          int page = AdminPlatform.cursor(r.query(PlatformUsersApi.AFTER));
          InvitationStatus status = r.query(PlatformUsersApi.STATUS);
          PlatformAdminData.InvitationPage found =
              AdminPlatform.call(client, () -> client.invitations(status == null ? null : status.name(), page, limit), null, null);
          List<AdminInvitation> items = new ArrayList<>();
          for (InvitationInfo i : found.invitations()) {
            items.add(invitation(own, i));
          }
          boolean more = (long) (page + 1) * limit < found.total();
          return Outcome.ok(new AdminApi.Page<>(List.copyOf(items), more ? String.valueOf(page + 1) : null));
        });

    routes.bind(
        operations.resend,
        r -> {
          String id = AdminPlatform.id(r.path(PlatformUsersApi.ID), "INVITATION_NOT_FOUND", "there is no such invitation");
          boolean link = Boolean.TRUE.equals(r.body().returnInviteLink());
          PlatformAdminData.ResendResult sent =
              AdminPlatform.write(client, () -> client.resendInvitation(id, !link, link), "INVITATION_NOT_FOUND", "there is no such invitation");
          return Outcome.ok(new PlatformUsersApi.ResendResult(sent.expiresAt(), link ? sent.inviteLink() : null)).header("Cache-Control", "no-store");
        });

    routes.bind(
        operations.revoke,
        r -> {
          String id = AdminPlatform.id(r.path(PlatformUsersApi.ID), "INVITATION_NOT_FOUND", "there is no such invitation");
          AdminPlatform.write(
              client,
              () -> {
                client.revokeInvitation(id);
                return Boolean.TRUE;
              },
              "INVITATION_NOT_FOUND",
              "there is no such invitation");
          return Outcome.noContent();
        });
  }

  /** The application's own roles among the wanted ones: {@code app:role}, or a short name (no colon). */
  private static List<String> ownRoles(String own, List<String> wanted) {
    List<String> out = new ArrayList<>();
    for (String role : wanted) {
      if (ownsRole(own, role)) {
        out.add(role);
      }
    }
    return List.copyOf(out);
  }

  private static boolean ownsRole(String own, String role) {
    return !role.contains(":") || role.startsWith(own + ":");
  }

  private static List<String> csv(@Nullable String text) {
    List<String> out = new ArrayList<>();
    if (text != null) {
      for (String part : text.split(",")) {
        if (!part.isBlank()) {
          out.add(part.trim());
        }
      }
    }
    return List.copyOf(out);
  }

  private static List<String> ownOnly(String own, List<String> roles) {
    List<String> out = new ArrayList<>();
    for (String role : roles) {
      if (role.startsWith(own + ":")) {
        out.add(role);
      }
    }
    return List.copyOf(out);
  }

  private static AdminUser user(String own, UserInfo u) {
    return new AdminUser(
        u.id(), u.email(), u.name(), u.active(), u.clientId(), u.idpType(), ownOnly(own, u.roles()), u.invitation(), u.lastLoginAt(), u.createdAt());
  }

  private static AdminInvitation invitation(String own, InvitationInfo i) {
    return new AdminInvitation(
        i.userId(),
        i.email(),
        i.name(),
        i.clientId(),
        ownOnly(own, i.roles()),
        "EXPIRED".equals(i.status()) ? InvitationStatus.EXPIRED : InvitationStatus.PENDING,
        i.createdAt(),
        i.expiresAt());
  }
}
