package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.context.Actor;
import io.flowcatalyst.framework.context.ApplicationCode;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Verifies field access tokens (contract section 15.2) without the proof: RS256 against the
 * platform's published keys (the same key cache and issuer as {@link PlatformTokens}), JOSE {@code
 * typ} {@code fc-field+jwt}, audience the application's code, {@code token_use} {@code field}, the
 * worker, client, device and session claims, and no {@code pcr} claim. The device's DPoP proof is
 * checked by the request resolver ({@link AuthModule.Builder#fieldWorkers}); this class answers
 * only "is this the platform's field token for this application".
 *
 * <p>Thread-safe. {@link AuthModule} provides one when field workers are enabled.
 */
public final class FieldTokens {

  private static final Pattern CLIENT_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]{1,100}");

  private final Jwt.KeySource keys;
  private final String issuer;
  private final ApplicationCode application;
  private final InstantSource clock;

  FieldTokens(Jwt.KeySource keys, String issuer, ApplicationCode application, InstantSource clock) {
    this.keys = keys;
    this.issuer = issuer;
    this.application = application;
    this.clock = clock;
  }

  /**
   * Creates a verifier that shares the platform keys and issuer of {@code tokens}.
   *
   * @param tokens the platform token verifier of the same application
   * @param application this application's code; a token's audience must equal it
   * @param clock the clock that decides whether a token is still valid
   * @return the verifier
   */
  public static FieldTokens create(PlatformTokens tokens, ApplicationCode application, InstantSource clock) {
    return new FieldTokens(tokens.platform(), tokens.platform().issuer(), application, clock);
  }

  /**
   * Verifies a field access token.
   *
   * @param token the compact token, without the {@code DPoP} scheme prefix
   * @return valid, invalid with a reason, or unavailable
   */
  public FieldTokenResult verify(String token) {
    try {
      return switch (Jwt.verify(token, keys)) {
        case Jwt.Checked.Refused r -> new FieldTokenResult.Invalid(r.reason());
        case Jwt.Checked.Ok ok -> claims(ok.header(), ok.claims());
      };
    } catch (PlatformUnavailableException e) {
      return new FieldTokenResult.Unavailable(e.getMessage() == null ? "the platform cannot be reached" : e.getMessage());
    }
  }

  private FieldTokenResult claims(JsonNode header, JsonNode claims) {
    Optional<String> refused = refusal(header, claims, clock.instant());
    if (refused.isPresent()) {
      return new FieldTokenResult.Invalid(refused.get());
    }
    String jkt = Jwt.text(claims.path("cnf"), "jkt");
    // a mode that is none of PERSONAL, SHARED and UNENROLLED gets the lesser (shared) one
    String modeText = Jwt.text(claims, "device_mode");
    FieldPrincipal.DeviceMode mode =
        "PERSONAL".equals(modeText)
            ? FieldPrincipal.DeviceMode.PERSONAL
            : "UNENROLLED".equals(modeText) ? FieldPrincipal.DeviceMode.UNENROLLED : FieldPrincipal.DeviceMode.SHARED;
    JsonNode act = claims.get("act");
    @Nullable String actorId = act != null && act.isObject() ? Jwt.text(act, "sub") : null;
    @Nullable Actor actor = null;
    if (actorId != null) {
      String actorType = Jwt.text(act, "typ");
      actor = new Actor(actorId, actorType == null ? "" : actorType, Jwt.text(act, "name"));
    }
    JsonNode testClaim = claims.get("test");
    boolean test = testClaim != null && testClaim.isBoolean() && testClaim.asBoolean();
    JsonNode authTime = claims.get("auth_time");
    @Nullable Instant signedIn = authTime != null && authTime.isNumber() ? Jwt.seconds(authTime) : null;
    List<String> amr = Jwt.texts(claims.get("amr"));
    FieldPrincipal worker =
        new FieldPrincipal(
            required(claims, "sub"),
            Jwt.text(claims, "worker_code"),
            Jwt.text(claims, "name"),
            required(claims, "client_id"),
            required(claims, "client_identifier"),
            application.value(),
            Jwt.text(claims, "device_id"), // absent on an unenrolled token
            mode,
            amr,
            signedIn,
            required(claims, "sid"),
            Jwt.seconds(claims.get("exp")),
            actor,
            test,
            Jwt.text(claims, "linked_principal_id")); // absent when the worker is not linked
    return new FieldTokenResult.Valid(worker, jkt == null ? "" : jkt);
  }

  private static String required(JsonNode claims, String name) {
    String value = Jwt.text(claims, name);
    if (value == null) {
      throw new IllegalStateException("claims were not checked");
    }
    return value;
  }

  private Optional<String> refusal(JsonNode header, JsonNode claims, Instant now) {
    if (!"fc-field+jwt".equals(Jwt.text(header, "typ"))) {
      return Optional.of("wrong_typ");
    }
    if (!issuer.equals(Jwt.text(claims, "iss"))) {
      return Optional.of("iss");
    }
    JsonNode aud = claims.get("aud");
    if (aud == null || !aud.isString() || !aud.asString().equals(application.value())) {
      return Optional.of("wrong_aud");
    }
    Optional<String> times = Jwt.times(claims, now);
    if (times.isPresent()) {
      return times;
    }
    if (!"field".equals(Jwt.text(claims, "token_use"))) {
      return Optional.of("wrong_token_use");
    }
    String sub = Jwt.text(claims, "sub");
    if (sub == null || !sub.startsWith("fwu_")) {
      return Optional.of("sub");
    }
    JsonNode cnf = claims.get("cnf");
    String clientIdentifier = Jwt.text(claims, "client_identifier");
    if (Jwt.text(claims, "client_id") == null
        || clientIdentifier == null
        || !CLIENT_IDENTIFIER.matcher(clientIdentifier).matches()
        || Jwt.text(claims, "sid") == null
        || cnf == null
        || !cnf.isObject()
        || Jwt.text(cnf, "jkt") == null
        || !claims.hasNonNull("exp")) {
      return Optional.of("claims");
    }
    JsonNode pcr = claims.get("pcr");
    if (pcr != null && !(pcr.isBoolean() && !pcr.asBoolean())) {
      return Optional.of("pcr");
    }
    return Optional.empty();
  }
}
