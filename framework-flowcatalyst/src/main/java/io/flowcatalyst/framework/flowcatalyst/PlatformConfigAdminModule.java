package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.flowcatalyst.PlatformConfigApi.ConfigItem;
import io.flowcatalyst.framework.flowcatalyst.PlatformConfigApi.ConfigList;
import io.flowcatalyst.framework.flowcatalyst.PlatformConfigApi.RevealedValue;
import io.flowcatalyst.framework.flowcatalyst.PlatformConfigApi.ValueType;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.secrets.SecretRef;
import io.flowcatalyst.framework.secrets.SecretResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The admin <b>platform-config</b> section (platform-config contract section 4): the application's
 * own configuration on the platform, listed, added, changed, deleted and (for a secret) revealed,
 * called as the application's service account after the framework has checked the signed-in user's
 * permission. List it after {@code AdminModule} and {@code FlowCatalystModule}.
 *
 * <p>Permissions: {@code {app}:admin:platform-config:view}, {@code :manage} and {@code :reveal}.
 * Reveal is separate from manage: writing a secret does not let the caller read it back. The
 * {@code :all-clients} lets a user name any client; otherwise only the user's own. The
 * application code in every platform path is the configured one. A secret's plaintext is in the
 * answer of the reveal operation, with {@code Cache-Control: no-store}, and nowhere else: not in a
 * list, not in the answer to a write, not in a log line.
 */
public final class PlatformConfigAdminModule implements Module {

  /** The module id. */
  public static final String ID = "admin-platform-config";

  private static final Logger LOG = LoggerFactory.getLogger(PlatformConfigAdminModule.class);
  private static final Pattern SECTION = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** The client of a context that acts for no particular client (the auth module's {@code _platform}). */
  private static final String NO_CLIENT = "_platform";

  private final ApplicationCode application;
  private final PlatformConfigApi.Operations operations;
  private final AdminSection section;

  private PlatformConfigAdminModule(ApplicationCode application) {
    this.application = application;
    this.operations = new PlatformConfigApi.Operations(view(application), manage(application), reveal(application));
    this.section =
        AdminSection.of("platform-config", "Platform config", "/admin/platform-config", view(application))
            .icon("i-lucide-key-round")
            .description("The configuration and secrets this application keeps on the platform.")
            .serving(operations.list, operations.set, operations.delete, operations.reveal);
  }

  /**
   * The module for an application.
   *
   * @param application the application code; it must equal {@code flowcatalyst.application}
   * @return the module
   */
  public static PlatformConfigAdminModule of(ApplicationCode application) {
    return new PlatformConfigAdminModule(application);
  }

  /**
   * The permission that opens the section and lists the configuration.
   *
   * @param application the application code
   * @return {@code {app}:admin:platform-config:view}
   */
  public static String view(ApplicationCode application) {
    return application.value() + ":admin:platform-config:view";
  }

  /**
   * The permission to add, change and delete properties.
   *
   * @param application the application code
   * @return {@code {app}:admin:platform-config:manage}
   */
  public static String manage(ApplicationCode application) {
    return application.value() + ":admin:platform-config:manage";
  }

  /**
   * The permission to read a secret's plaintext.
   *
   * @param application the application code
   * @return {@code {app}:admin:platform-config:reveal}
   */
  public static String reveal(ApplicationCode application) {
    return application.value() + ":admin:platform-config:reveal";
  }

  /**
   * The permission to name any client in the section, instead of only the signed-in user's own.
   *
   * @param application the application code
   * @return {@code {app}:admin:platform-config:all-clients}
   */
  public static String allClients(ApplicationCode application) {
    return application.value() + ":admin:platform-config:all-clients";
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, section);
  }

  @Override
  public void boot(App app) {
    PlatformClient client = app.get(PlatformClient.class);
    if (client.isEnabled() && !client.application().equals(application)) {
      throw new IllegalStateException(
          "PlatformConfigAdminModule is for application '"
              + application.value()
              + "' but flowcatalyst.application is '"
              + client.application().value()
              + "'");
    }
    Routes routes = app.get(Routes.class).forModule(ID);
    // Optional: when the application has no secrets module there is no cache to keep in step.
    java.util.Optional<SecretResolver> secrets = app.find(SecretResolver.class);

    routes.bind(
        operations.list,
        r -> {
          Context ctx = r.context();
          String only = AdminPlatform.blankToNull(r.query(PlatformConfigApi.SECTION_FILTER));
          if (only != null) {
            section(only);
          }
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          if (clientId != null) {
            mayActFor(ctx, client, clientId);
          }
          boolean everyClient = ctx.hasPermission(allClients(application));
          List<PlatformConfigData.Property> found = AdminPlatform.call(client, client::configProperties, null, null);
          // Rows carry the stored id; a filter given as an identifier is compared as the stored id it is.
          // Resolved after the listing, which names each row's identifier too.
          String filterId = clientId == null ? null : client.canonicalClient(clientId);
          List<ConfigItem> items = new ArrayList<>();
          for (PlatformConfigData.Property p : found) {
            boolean show =
                filterId != null
                    ? filterId.equals(p.clientId())
                    : everyClient || p.clientId() == null || ownClient(ctx, client, p.clientId());
            if ((only == null || only.equals(p.section())) && show) {
              items.add(item(p.section(), p.property(), p.clientId(), p));
            }
          }
          return Outcome.ok(new ConfigList(List.copyOf(items)));
        });

    routes.bind(
        operations.set,
        r -> {
          Context ctx = r.context();
          String section = section(r.path(PlatformConfigApi.SECTION));
          String property = property(r.path(PlatformConfigApi.PROPERTY));
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          if (clientId != null) {
            mayActFor(ctx, client, clientId);
          }
          PlatformConfigApi.SetBody body = r.body();
          PlatformConfigData.SetRequest request =
              new PlatformConfigData.SetRequest(
                  body.value(), body.valueType().name(), AdminPlatform.blankToNull(body.description()));
          @Nullable String onBehalfOf = behalf(ctx);
          PlatformConfigData.Property stored =
              AdminPlatform.write(client, () -> client.setConfigProperty(section, property, clientId, request, onBehalfOf), null, null);
          evict(secrets, clientId, property);
          LOG.info(
              "platform config set by {}: {}/{} client {} as {}",
              who(ctx.principal()),
              section,
              property,
              clientId,
              body.valueType());
          // The answer is built from what was asked for and the platform's timestamp, and a secret is masked
          // whatever the platform sent. The client is the stored id when the platform said it.
          return Outcome.ok(
              item(
                  section,
                  property,
                  stored.clientId() != null ? stored.clientId() : clientId,
                  withType(stored, body.valueType(), body.description())));
        });

    routes.bind(
        operations.delete,
        r -> {
          Context ctx = r.context();
          String section = section(r.path(PlatformConfigApi.SECTION));
          String property = property(r.path(PlatformConfigApi.PROPERTY));
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          if (clientId != null) {
            mayActFor(ctx, client, clientId);
          }
          @Nullable String onBehalfOf = behalf(ctx);
          AdminPlatform.write(
              client,
              () -> {
                client.deleteConfigProperty(section, property, clientId, onBehalfOf);
                return Boolean.TRUE;
              },
              null,
              null);
          evict(secrets, clientId, property);
          LOG.info("platform config deleted by {}: {}/{} client {}", who(ctx.principal()), section, property, clientId);
          return Outcome.noContent();
        });

    routes.bind(
        operations.reveal,
        r -> {
          Context ctx = r.context();
          String section = section(r.path(PlatformConfigApi.SECTION));
          String property = property(r.path(PlatformConfigApi.PROPERTY));
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          if (clientId != null) {
            mayActFor(ctx, client, clientId);
          }
          @Nullable String onBehalfOf = behalf(ctx);
          PlatformConfigData.Revealed revealed =
              AdminPlatform.call(
                  client,
                  () -> client.revealConfigSecret(section, property, clientId, onBehalfOf),
                  "CONFIG_NOT_FOUND",
                  "there is no such property");
          LOG.info("platform config revealed by {}: {}/{} client {}", who(ctx.principal()), section, property, clientId);
          return Outcome.ok(
                  new RevealedValue(
                      section, property, revealed.clientId() != null ? revealed.clientId() : clientId, revealed.value(), revealed.updatedAt()))
              .header("Cache-Control", "no-store");
        });
  }

  /**
   * Whether the signed-in user may act for the client, or {@code 403 CLIENT_FORBIDDEN} with no platform
   * call. The framework's request context carries one client (the user's own), not the user's list, so
   * the rule is: a user holding {@code {app}:admin:platform-config:all-clients} may name any client;
   * any other user only the context's client, by identifier or (once this process has seen the pair)
   * by the stored id. The platform confines the application's account again.
   */
  private void mayActFor(Context ctx, PlatformClient client, String clientId) {
    if (ctx.hasPermission(allClients(application))) {
      return;
    }
    if (ownClient(ctx, client, clientId)) {
      return;
    }
    throw new HttpException(403, "CLIENT_FORBIDDEN", "you may not manage the configuration of that client");
  }

  /** Whether a client reference (id or identifier) is the context's own client. */
  private static boolean ownClient(Context ctx, PlatformClient client, String reference) {
    String own = ctx.client().value();
    if (NO_CLIENT.equals(own)) {
      return false;
    }
    return own.equals(reference) || client.canonicalClient(own).equals(client.canonicalClient(reference));
  }

  /** A write changed what a platform secrets store may have cached: drop that entry, whichever store holds it. */
  private static void evict(java.util.Optional<SecretResolver> secrets, @Nullable String clientId, String property) {
    if (clientId == null || secrets.isEmpty()) {
      return;
    }
    try {
      secrets.get().evictEverywhere(new ClientId(clientId), property);
    } catch (IllegalArgumentException notAClientId) {
      // a reference no secret store could have been asked for
    }
  }

  /** The section as it may appear in a platform path, or a {@code 400} without a call. */
  private static String section(String text) {
    if (!SECTION.matcher(text).matches()) {
      throw new HttpException(400, "INVALID_NAME", "a section name is a lower-case letter followed by up to 63 lower-case letters, digits or hyphens");
    }
    return text;
  }

  /**
   * The property as it may appear in a platform path, or a {@code 400} without a call: exactly the
   * names the {@code platform} secrets driver can resolve ({@link SecretRef#isValidName}).
   */
  private static String property(String text) {
    if (!SecretRef.isValidName(text)) {
      throw new HttpException(
          400,
          "INVALID_NAME",
          "a property name is lower-case letters and digits in groups separated by single hyphens, at most " + SecretRef.NAME_MAX + " characters");
    }
    return text;
  }

  /** The signed-in principal as the platform's audit row and the log name it (an id; never a token). */
  private static String who(@Nullable Object principal) {
    return principal == null ? "(no principal)" : principal.toString();
  }

  /** What the platform's audit row records as the person: the principal's id, or nothing when there is none. */
  private static @Nullable String behalf(Context ctx) {
    PrincipalId principal = ctx.principal();
    return principal == null ? null : principal.value();
  }

  /** What the platform stored, with the type and description the caller asked for when it left them out. */
  private static PlatformConfigData.Property withType(PlatformConfigData.Property stored, ValueType type, @Nullable String description) {
    return new PlatformConfigData.Property(
        stored.section(),
        stored.property(),
        stored.clientId(),
        type.name(),
        stored.value(),
        stored.description() != null ? stored.description() : AdminPlatform.blankToNull(description),
        stored.updatedAt());
  }

  /** A property as the page sees it: anything not plainly {@code PLAIN} is a secret, and a secret is masked. */
  private static ConfigItem item(String section, String property, @Nullable String clientId, PlatformConfigData.Property p) {
    boolean plain = PlatformConfigData.PLAIN.equals(p.valueType());
    return new ConfigItem(
        section,
        property,
        clientId,
        plain ? ValueType.PLAIN : ValueType.SECRET,
        plain ? p.value() : PlatformConfigApi.MASK,
        p.description(),
        p.updatedAt());
  }
}
