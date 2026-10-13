package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.PrincipalId;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sessions and login records over a {@link SessionStore} (contract section 6): the JSON shapes,
 * the idle and absolute timeouts, and when {@code lastSeenAt} is written back.
 */
final class SessionManager {

  static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);

  /** A browser session as stored. */
  record SessionData(Principal principal, Instant authTime, Instant createdAt, Instant lastSeenAt, Instant absoluteExpiresAt) {}

  /** A login in progress. */
  record LoginData(String state, String nonce, String codeVerifier, String returnTo, Instant createdAt) {}

  /**
   * A live session after a read.
   *
   * @param handle the handle to send from now on
   * @param handleChanged whether the cookie must be set again
   */
  record Live(SessionData data, String handle, boolean handleChanged, Instant idleExpiresAt) {}

  private final SessionStore store;
  private final InstantSource clock;
  private final Duration idle;
  private final Duration absolute;
  private final Duration touchAfter;

  SessionManager(SessionStore store, InstantSource clock, Duration idle, Duration absolute) {
    if (idle.isNegative() || idle.isZero() || absolute.isNegative() || absolute.isZero()) {
      throw new IllegalArgumentException("session timeouts must be positive");
    }
    this.store = store;
    this.clock = clock;
    this.idle = idle;
    this.absolute = absolute;
    Duration half = idle.dividedBy(2);
    this.touchAfter = half.compareTo(Duration.ofMinutes(1)) < 0 ? half : Duration.ofMinutes(1);
  }

  SessionStore store() {
    return store;
  }

  java.time.Instant now() {
    return clock.instant();
  }

  Duration idleTimeout() {
    return idle;
  }

  Duration absoluteTimeout() {
    return absolute;
  }

  /** Creates a session; returns its handle. */
  String createSession(Principal principal, Instant authTime) {
    Instant now = clock.instant();
    SessionData data = new SessionData(withoutScopes(principal), authTime, now, now, now.plus(absolute));
    return store.create(SessionStore.SESSION, json(data), storeExpiry(data));
  }

  /** Reads a session, deleting it when a timeout has passed, writing {@code lastSeenAt} back when due. */
  Optional<Live> session(String handle) {
    Optional<String> json = store.read(SessionStore.SESSION, handle);
    if (json.isEmpty()) {
      return Optional.empty();
    }
    SessionData data = parseSession(json.get());
    Instant now = clock.instant();
    if (data == null || !now.isBefore(data.absoluteExpiresAt()) || !now.isBefore(data.lastSeenAt().plus(idle))) {
      store.delete(SessionStore.SESSION, handle);
      return Optional.empty();
    }
    if (Duration.between(data.lastSeenAt(), now).compareTo(touchAfter) >= 0) {
      SessionData touched = new SessionData(data.principal(), data.authTime(), data.createdAt(), now, data.absoluteExpiresAt());
      String next = store.update(SessionStore.SESSION, handle, json(touched), storeExpiry(touched));
      return Optional.of(new Live(touched, next, !next.equals(handle), idleExpiry(touched)));
    }
    return Optional.of(new Live(data, handle, false, idleExpiry(data)));
  }

  void deleteSession(String handle) {
    store.delete(SessionStore.SESSION, handle);
  }

  String createLogin(LoginData login) {
    return store.create(SessionStore.LOGIN, json(login), login.createdAt().plus(LOGIN_LIFETIME));
  }

  /** Reads and deletes a login record: it can complete once. */
  Optional<LoginData> takeLogin(String handle) {
    return store.take(SessionStore.LOGIN, handle).map(SessionManager::parseLogin).filter(l -> l != null);
  }

  Instant idleExpiry(SessionData data) {
    Instant idleEnd = data.lastSeenAt().plus(idle);
    return idleEnd.isBefore(data.absoluteExpiresAt()) ? idleEnd : data.absoluteExpiresAt();
  }

  private Instant storeExpiry(SessionData data) {
    return idleExpiry(data);
  }

  private static Principal withoutScopes(Principal p) {
    return new Principal(p.id(), p.kind(), p.name(), p.email(), p.tier(), p.homeClient(), p.clients(), p.applications(), p.roles(), Set.of());
  }

  // ---- JSON (contract sections 6.1 and 6.2) --------------------------------------------------

  static String json(SessionData d) {
    ObjectNode root = Records.MAPPER.createObjectNode();
    root.put("v", 1);
    ObjectNode principal = root.putObject("principal");
    principal.put("id", d.principal().id().value());
    principal.put("kind", d.principal().kind().name());
    putIfSet(principal, "name", d.principal().name());
    putIfSet(principal, "email", d.principal().email());
    putIfSet(root, "tier", d.principal().tier());
    putIfSet(root, "homeClient", d.principal().homeClient());
    array(root.putArray("clients"), d.principal().clients());
    array(root.putArray("applications"), d.principal().applications());
    array(root.putArray("roles"), d.principal().roles());
    root.put("authTime", d.authTime().toString());
    root.put("createdAt", d.createdAt().toString());
    root.put("lastSeenAt", d.lastSeenAt().toString());
    root.put("absoluteExpiresAt", d.absoluteExpiresAt().toString());
    return Records.MAPPER.writeValueAsString(root);
  }

  static @Nullable SessionData parseSession(String json) {
    try {
      JsonNode root = Records.MAPPER.readTree(json);
      JsonNode p = root.path("principal");
      String id = p.path("id").asString("");
      if (root.path("v").asInt(0) != 1 || id.isBlank()) {
        return null;
      }
      Principal principal =
          new Principal(
              new PrincipalId(id),
              PrincipalKind.of(p.path("kind").asString("USER")),
              textOrNull(p, "name"),
              textOrNull(p, "email"),
              textOrNull(root, "tier"),
              textOrNull(root, "homeClient"),
              Jwt.texts(root.get("clients")),
              Jwt.texts(root.get("applications")),
              Jwt.texts(root.get("roles")),
              Set.of());
      return new SessionData(
          principal,
          Instant.parse(root.path("authTime").asString("")),
          Instant.parse(root.path("createdAt").asString("")),
          Instant.parse(root.path("lastSeenAt").asString("")),
          Instant.parse(root.path("absoluteExpiresAt").asString("")));
    } catch (JacksonException | DateTimeParseException | IllegalArgumentException e) {
      return null;
    }
  }

  static String json(LoginData d) {
    ObjectNode root = Records.MAPPER.createObjectNode();
    root.put("v", 1);
    root.put("state", d.state());
    root.put("nonce", d.nonce());
    root.put("codeVerifier", d.codeVerifier());
    root.put("returnTo", d.returnTo());
    root.put("createdAt", d.createdAt().toString());
    return Records.MAPPER.writeValueAsString(root);
  }

  static @Nullable LoginData parseLogin(String json) {
    try {
      JsonNode root = Records.MAPPER.readTree(json);
      if (root.path("v").asInt(0) != 1) {
        return null;
      }
      String state = root.path("state").asString("");
      String nonce = root.path("nonce").asString("");
      String verifier = root.path("codeVerifier").asString("");
      String returnTo = root.path("returnTo").asString("");
      if (state.isEmpty() || nonce.isEmpty() || verifier.isEmpty() || !ReturnToPolicy.isSameOriginPath(returnTo)) {
        return null;
      }
      return new LoginData(state, nonce, verifier, returnTo, Instant.parse(root.path("createdAt").asString("")));
    } catch (JacksonException | DateTimeParseException e) {
      return null;
    }
  }

  private static void putIfSet(ObjectNode node, String name, @Nullable String value) {
    if (value != null) {
      node.put(name, value);
    }
  }

  private static void array(ArrayNode node, List<String> values) {
    for (String v : new ArrayList<>(values)) {
      node.add(v);
    }
  }

  private static @Nullable String textOrNull(JsonNode node, String name) {
    JsonNode v = node.get(name);
    return v != null && v.isString() && !v.asString().isEmpty() ? v.asString() : null;
  }
}
