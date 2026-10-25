package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.secrets.CachePolicy;
import io.flowcatalyst.framework.secrets.SecretResult;
import io.flowcatalyst.framework.secrets.SecretStore;
import io.flowcatalyst.framework.secrets.SecretStoreException;
import io.flowcatalyst.framework.secrets.SecretStoreFactory;
import io.flowcatalyst.framework.secrets.SecretStores;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code platform} secrets driver (platform-config contract section 3, secrets contract section
 * 4): a per-client secret is a platform configuration property of this application, in one section,
 * at that client's scope, read with the platform's reveal call as the application's service account.
 *
 * <pre>{@code
 * SecretsModule.builder()
 *     .store("platform", PlatformSecrets.store())
 *     .store("ftp", PlatformSecrets.store().section("ftp").withCache(CachePolicy.of(Duration.ofMinutes(1), 1000)))
 *     .defaultStore("platform")
 *     .build()
 * }</pre>
 *
 * <p>The client of a lookup is the platform's client id ({@code clt_...}) or the client's identifier
 * ({@code acme}): the platform resolves either to the same scope, so an application that names its
 * clients by code needs no translation. A client the platform does not know is not found. There is no
 * fall-back to the global value of the property.
 */
public final class PlatformSecrets {
  private PlatformSecrets() {}

  /** The section the driver reads unless told another. */
  public static final String DEFAULT_SECTION = "secrets";

  /** The platform's codes for "no such property" and "no such client" on the config calls. */
  static final java.util.Set<String> NOT_FOUND_CODES = java.util.Set.of("Config_NOT_FOUND", "Client_NOT_FOUND");

  private static final Pattern SECTION = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  /** A client reference: the platform's client id, or a client identifier (a slug). No separators, no dots. */
  private static final Pattern PLATFORM_CLIENT =
      Pattern.compile("clt_[A-Za-z0-9]{1,60}|[A-Za-z0-9]([A-Za-z0-9-]{0,98}[A-Za-z0-9])?");

  /**
   * The driver, reading section {@code secrets} with {@link CachePolicy#defaults()}.
   *
   * @return the store
   */
  public static Store store() {
    return new Store(DEFAULT_SECTION, CachePolicy.defaults());
  }

  /**
   * The {@code platform} driver.
   *
   * @param section the platform configuration section that holds the secrets, {@code [a-z][a-z0-9-]{0,63}}
   * @param cache how found values are cached
   */
  public record Store(String section, CachePolicy cache) implements SecretStoreFactory {

    /** Checks the section. */
    public Store {
      if (!SECTION.matcher(section).matches()) {
        throw new IllegalArgumentException("section must match [a-z][a-z0-9-]{0,63}, was '" + section + "'");
      }
    }

    /**
     * A copy reading another section.
     *
     * @param value the section
     * @return the copy
     */
    public Store section(String value) {
      return new Store(value, cache);
    }

    /**
     * A copy with another cache policy.
     *
     * @param value the policy
     * @return the copy
     */
    public Store withCache(CachePolicy value) {
      return new Store(section, value);
    }

    @Override
    public SecretStore create(String name, Lookup lookup) {
      PlatformClient client = lookup.get(PlatformClient.class);
      if (!client.isEnabled()) {
        throw new IllegalStateException("the flowcatalyst module is disabled (flowcatalyst.enabled)");
      }
      return SecretStores.cached(new PlatformSecretStore(name, client, section), cache, lookup);
    }
  }

  private static final class PlatformSecretStore implements SecretStore {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformSecretStore.class);

    private final String instance;
    private final PlatformClient platform;
    private final String section;

    PlatformSecretStore(String instance, PlatformClient platform, String section) {
      this.instance = instance;
      this.platform = platform;
      this.section = section;
    }

    @Override
    public ClientId canonical(ClientId client) {
      String stored = platform.canonicalClient(client.value());
      return stored.equals(client.value()) ? client : new ClientId(stored);
    }

    @Override
    public SecretResult find(ClientId client, String name) {
      if (!PLATFORM_CLIENT.matcher(client.value()).matches()) {
        return new SecretResult.Refused("the client must be the platform's client id (clt_...) or its identifier");
      }
      PlatformConfigData.Revealed revealed;
      try {
        revealed = platform.revealConfigSecret(section, name, client.value());
      } catch (PlatformException e) {
        return outcome(e, client, name);
      } catch (RuntimeException e) {
        throw failure("could not be read", client, name);
      }
      if (revealed.value().isEmpty()) {
        throw failure("answered without a value", client, name);
      }
      LOG.debug("secret store '{}': found {} for client {}", instance, name, client);
      return new SecretResult.Found(Secret.of(revealed.value()));
    }

    private SecretResult outcome(PlatformException e, ClientId client, String name) {
      switch (e.kind()) {
        case NOT_FOUND -> {
          // Only the platform's own "no such property / no such client" codes mean not found. Any other 404
          // (a route that does not exist, a proxy's page) says nothing about the secret.
          if (e.code() != null && NOT_FOUND_CODES.contains(e.code())) {
            return new SecretResult.NotFound();
          }
          throw failure("could not be read: " + e.getMessage() + " (not a platform not-found answer)", client, name);
        }
        case REJECTED -> {
          if ("NOT_A_SECRET".equals(e.code())) {
            return new SecretResult.Refused("the platform property is not a secret");
          }
          throw failure("was refused by the platform (" + e.status() + (e.code() == null ? "" : " " + e.code()) + ")", client, name);
        }
        case FORBIDDEN -> {
          throw failure(
              "was not read: application '" + platform.application().value() + "' is not authorised to reveal it", client, name);
        }
        default -> {
          // The exception's cause is dropped: a decoder's message can quote the text it could not read.
          throw failure("could not be read: " + e.getMessage(), client, name);
        }
      }
    }

    private SecretStoreException failure(String what, ClientId client, String name) {
      return new SecretStoreException(
          "secret store '" + instance + "' (platform): secret '" + name + "' of client '" + client + "' " + what, null);
    }

    @Override
    public String toString() {
      return "PlatformSecretStore[" + instance + ", section=" + section + "]";
    }
  }
}
