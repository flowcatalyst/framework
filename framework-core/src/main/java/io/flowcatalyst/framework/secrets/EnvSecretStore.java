package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.EnvironmentVariables;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code env} driver (secrets contract, section 4): {@code prefix + E(client) + "__" +
 * E(name)}, where E upper-cases and turns {@code -} into {@code _}. Clients and names are limited
 * to lowercase letters, digits and single hyphens, which makes the encoding injective; a client
 * outside that is refused, never approximated.
 */
final class EnvSecretStore implements SecretStore {

  static final Pattern PREFIX = Pattern.compile("[A-Z][A-Z0-9_]*_");
  static final List<String> FORBIDDEN_PREFIXES =
      List.of("APP_", "HTTP_", "DATABASE_", "REDIS_", "LOCKS_", "FLOWCATALYST_", "SECRETS_");
  private static final Pattern PART = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

  private final String instance;
  private final String prefix;
  private final EnvironmentVariables env;

  EnvSecretStore(String instance, String prefix, EnvironmentVariables env) {
    checkPrefix(prefix);
    this.instance = instance;
    this.prefix = prefix;
    this.env = env;
  }

  static void checkPrefix(String prefix) {
    if (prefix.length() < 4 || !PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException(
          "prefix must match [A-Z][A-Z0-9_]*_ and be at least 4 characters");
    }
    for (String forbidden : FORBIDDEN_PREFIXES) {
      if (prefix.startsWith(forbidden)) {
        throw new IllegalArgumentException(
            "prefix must not start with " + forbidden + " (a framework component's variables)");
      }
    }
  }

  /** The variable a (client, name) reads, or empty when the client cannot be encoded. */
  Optional<String> variable(ClientId client, String name) {
    Names.check(name);
    if (!PART.matcher(client.value()).matches()) {
      return Optional.empty();
    }
    return Optional.of(prefix + encode(client.value()) + "__" + encode(name));
  }

  private static String encode(String part) {
    return part.toUpperCase(Locale.ROOT).replace('-', '_');
  }

  @Override
  public SecretResult find(ClientId client, String name) {
    Optional<String> variable = variable(client, name);
    if (variable.isEmpty()) {
      return new SecretResult.Refused(
          "secret store '"
              + instance
              + "' (env) cannot encode client '"
              + client.value()
              + "': only lowercase letters, digits and single hyphens");
    }
    Optional<String> value = env.get(variable.get());
    if (value.isEmpty() || value.get().isEmpty()) {
      return new SecretResult.NotFound();
    }
    return new SecretResult.Found(Secret.of(value.get()));
  }

  @Override
  public String toString() {
    return "EnvSecretStore[" + instance + "]";
  }
}
