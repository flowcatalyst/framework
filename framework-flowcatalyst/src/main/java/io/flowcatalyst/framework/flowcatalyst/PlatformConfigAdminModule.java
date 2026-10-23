package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.context.ApplicationCode;
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
 * application code in every platform path is the configured one. A secret's plaintext is in the
 * answer of the reveal operation, with {@code Cache-Control: no-store}, and nowhere else: not in a
 * list, not in the answer to a write, not in a log line.
 */
public final class PlatformConfigAdminModule implements Module {

  /** The module id. */
  public static final String ID = "admin-platform-config";

  private static final Logger LOG = LoggerFactory.getLogger(PlatformConfigAdminModule.class);
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

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

    routes.bind(
        operations.list,
        r -> {
          String only = AdminPlatform.blankToNull(r.query(PlatformConfigApi.SECTION_FILTER));
          if (only != null) {
            name(only);
          }
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          List<PlatformConfigData.Property> found = AdminPlatform.call(client, client::configProperties, null, null);
          List<ConfigItem> items = new ArrayList<>();
          for (PlatformConfigData.Property p : found) {
            if ((only == null || only.equals(p.section())) && (clientId == null || clientId.equals(p.clientId()))) {
              items.add(item(p.section(), p.property(), p.clientId(), p));
            }
          }
          return Outcome.ok(new ConfigList(List.copyOf(items)));
        });

    routes.bind(
        operations.set,
        r -> {
          String section = name(r.path(PlatformConfigApi.SECTION));
          String property = name(r.path(PlatformConfigApi.PROPERTY));
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          PlatformConfigApi.SetBody body = r.body();
          PlatformConfigData.SetRequest request =
              new PlatformConfigData.SetRequest(
                  body.value(), body.valueType().name(), AdminPlatform.blankToNull(body.description()));
          PlatformConfigData.Property stored =
              AdminPlatform.write(client, () -> client.setConfigProperty(section, property, clientId, request), null, null);
          LOG.info(
              "platform config set by {}: {}/{} client {} as {}",
              who(r.context().principal()),
              section,
              property,
              clientId,
              body.valueType());
          // The answer is built from what was asked for and the platform's timestamp, and a secret is masked
          // whatever the platform sent.
          return Outcome.ok(item(section, property, clientId, withType(stored, body.valueType(), body.description())));
        });

    routes.bind(
        operations.delete,
        r -> {
          String section = name(r.path(PlatformConfigApi.SECTION));
          String property = name(r.path(PlatformConfigApi.PROPERTY));
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          AdminPlatform.write(
              client,
              () -> {
                client.deleteConfigProperty(section, property, clientId);
                return Boolean.TRUE;
              },
              null,
              null);
          LOG.info("platform config deleted by {}: {}/{} client {}", who(r.context().principal()), section, property, clientId);
          return Outcome.noContent();
        });

    routes.bind(
        operations.reveal,
        r -> {
          String section = name(r.path(PlatformConfigApi.SECTION));
          String property = name(r.path(PlatformConfigApi.PROPERTY));
          String clientId = AdminPlatform.blankToNull(r.query(PlatformConfigApi.CLIENT));
          PlatformConfigData.Revealed revealed =
              AdminPlatform.call(
                  client, () -> client.revealConfigSecret(section, property, clientId), "CONFIG_NOT_FOUND", "there is no such property");
          LOG.info("platform config revealed by {}: {}/{} client {}", who(r.context().principal()), section, property, clientId);
          return Outcome.ok(new RevealedValue(section, property, clientId, revealed.value(), revealed.updatedAt()))
              .header("Cache-Control", "no-store");
        });
  }

  /** The name as it may appear in a platform path, or a {@code 400} without a call. */
  private static String name(String text) {
    if (!NAME.matcher(text).matches()) {
      throw new HttpException(400, "INVALID_NAME", "a section or property name is a lower-case letter followed by up to 63 lower-case letters, digits or hyphens");
    }
    return text;
  }

  private static String who(@Nullable Object principal) {
    return principal == null ? "(no principal)" : principal.toString();
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
