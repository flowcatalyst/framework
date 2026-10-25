package io.flowcatalyst.framework.secrets;

import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.context.ClientId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a {@link SecretRef} for the client the caller acts for (secrets contract, section 3).
 * Provided by {@link SecretsModule}.
 *
 * <pre>{@code
 * Secret password = resolver.require(ctx.client(), SecretRef.parse("files:sftp-password"));
 * }</pre>
 */
public final class SecretResolver {

  private static final Logger LOG = LoggerFactory.getLogger(SecretResolver.class);

  private final Map<String, SecretStore> stores;
  private final @Nullable String defaultStore;

  private SecretResolver(Map<String, SecretStore> stores, @Nullable String defaultStore) {
    this.stores = Map.copyOf(stores);
    this.defaultStore = defaultStore;
    if (defaultStore != null && !this.stores.containsKey(defaultStore)) {
      throw new IllegalArgumentException("no secret store named '" + defaultStore + "'");
    }
  }

  /**
   * A resolver over given stores, for tests and for applications that build stores themselves.
   *
   * @param stores stores by instance name
   * @param defaultStore the store a reference without a store uses, or null for none
   * @return the resolver
   */
  public static SecretResolver of(Map<String, SecretStore> stores, @Nullable String defaultStore) {
    return new SecretResolver(new LinkedHashMap<>(stores), defaultStore);
  }

  /**
   * Looks a secret up.
   *
   * @param client the client the caller acts for (never taken from the reference)
   * @param ref the reference
   * @return {@code Found}, {@code NotFound} or {@code Refused}
   * @throws IllegalArgumentException for an unknown store
   * @throws IllegalStateException when the reference names no store and there is no default
   * @throws SecretStoreException when the store cannot be read
   */
  public SecretResult resolve(ClientId client, SecretRef ref) {
    String name = storeName(ref);
    SecretResult result = store(name).find(client, ref.name());
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "secret {} of client {} from store {}: {}",
          ref.name(),
          client.value(),
          name,
          result.getClass().getSimpleName());
    }
    return result;
  }

  /**
   * Looks a secret up and requires it.
   *
   * @param client the client the caller acts for
   * @param ref the reference
   * @return the secret
   * @throws SecretNotFoundException when it is not found or the store refused the lookup
   * @throws SecretStoreException when the store cannot be read
   */
  public Secret require(ClientId client, SecretRef ref) {
    return switch (resolve(client, ref)) {
      case SecretResult.Found found -> found.secret();
      case SecretResult.NotFound n ->
          throw new SecretNotFoundException(
              "no secret " + ref.name() + " for client " + client.value() + " in store " + storeName(ref));
      case SecretResult.Refused refused ->
          throw new SecretNotFoundException(
              "secret " + ref.name() + " for client " + client.value() + " refused: " + refused.reason());
    };
  }

  /**
   * Drops a cached value, so the next lookup reads the store (for example after rotation).
   *
   * @param client the client
   * @param ref the reference
   */
  public void evict(ClientId client, SecretRef ref) {
    store(storeName(ref)).evict(client, ref.name());
  }

  /**
   * Drops one cached value from every store, whichever store a reference would name (for a change
   * made outside the resolver, such as the admin page writing a platform secret). A store that
   * holds no such entry ignores it.
   *
   * @param client the client, under any of its names
   * @param name the secret's name
   */
  public void evictEverywhere(ClientId client, String name) {
    stores.values().forEach(s -> s.evict(client, name));
  }

  /** Drops every cached value of every store. */
  public void evictAll() {
    stores.values().forEach(SecretStore::evictAll);
  }

  private String storeName(SecretRef ref) {
    String name = ref.store() != null ? ref.store() : defaultStore;
    if (name == null) {
      throw new IllegalStateException(
          "no secret store is configured" + (stores.isEmpty() ? "" : " as the default"));
    }
    return name;
  }

  private SecretStore store(String name) {
    SecretStore store = stores.get(name);
    if (store == null) {
      throw new IllegalArgumentException(
          "no secret store named '" + name + "'; known: " + String.join(", ", stores.keySet()));
    }
    return store;
  }
}
