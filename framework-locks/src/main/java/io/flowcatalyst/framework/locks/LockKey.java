package io.flowcatalyst.framework.locks;

import io.flowcatalyst.framework.context.ClientId;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a lock protects: a name, at service scope or within one client.
 *
 * <p>Stored forms are {@code svc:<name>} and {@code client:<clientId>:<name>}. A client-scoped key
 * never equals a service-scoped one, and keys of different clients never collide.
 *
 * @param scope service or client
 * @param client the client, for client scope; null for service scope
 * @param name matches {@code [A-Za-z0-9._:-]{1,200}}
 */
public record LockKey(Scope scope, @Nullable ClientId client, String name) {

  /** The scope of a key. */
  public enum Scope {
    /** One lock per deployment. */
    SERVICE,
    /** One lock per client. */
    CLIENT
  }

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,200}");

  // ClientId never contains ':', so "client:<id>:<name>" is unambiguous: the first ':' after
  // the prefix ends the client id.

  /** Validates the parts. */
  public LockKey {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("lock name must match [A-Za-z0-9._:-]{1,200}: " + name);
    }
    if (scope == Scope.SERVICE && client != null) {
      throw new IllegalArgumentException("a service-scoped lock key has no client");
    }
    if (scope == Scope.CLIENT && client == null) {
      throw new IllegalArgumentException("a client-scoped lock key needs a client");
    }
  }

  /**
   * A service-scoped key.
   *
   * @param name the lock name
   * @return the key
   * @throws IllegalArgumentException when the name is not allowed
   */
  public static LockKey service(String name) {
    return new LockKey(Scope.SERVICE, null, name);
  }

  /**
   * A client-scoped key.
   *
   * @param client the client
   * @param name the lock name
   * @return the key
   * @throws IllegalArgumentException when the name or the client id is not allowed
   */
  public static LockKey client(ClientId client, String name) {
    return new LockKey(Scope.CLIENT, client, name);
  }

  /**
   * The form drivers store.
   *
   * @return {@code svc:<name>} or {@code client:<clientId>:<name>}
   */
  public String stored() {
    return client == null ? "svc:" + name : "client:" + client.value() + ":" + name;
  }

  @Override
  public String toString() {
    return stored();
  }
}
