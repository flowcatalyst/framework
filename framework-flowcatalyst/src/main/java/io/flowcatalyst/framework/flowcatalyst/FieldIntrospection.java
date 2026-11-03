package io.flowcatalyst.framework.flowcatalyst;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The platform's answer to {@code POST /api/field-tokens/introspect}: {@code {active, sub, clientId,
 * workerCode, deviceId, appCode, amr, authTime, exp}}. Everything but {@code active} is absent for
 * an inactive token.
 *
 * @param active true only while the token is unexpired, its login family has a live session and
 *     both the worker and the device are active
 * @param sub the worker's principal id
 * @param clientId the client's platform id
 * @param workerCode the worker's code
 * @param deviceId the device
 * @param appCode the application the token is for
 * @param amr how the worker authenticated
 * @param authTime when the worker signed in
 * @param exp when the token expires
 */
public record FieldIntrospection(
    boolean active,
    @Nullable String sub,
    @Nullable String clientId,
    @Nullable String workerCode,
    @Nullable String deviceId,
    @Nullable String appCode,
    List<String> amr,
    @Nullable Instant authTime,
    @Nullable Instant exp) {

  /** Copies the list. */
  public FieldIntrospection {
    amr = List.copyOf(amr);
  }

  static FieldIntrospection parse(JsonNode answer) {
    if (answer == null || !answer.isObject()) {
      throw new PlatformException("the platform's introspection answer is not a JSON object");
    }
    JsonNode active = answer.get("active");
    if (active == null || !active.isBoolean()) {
      throw new PlatformException("the platform's introspection answer has no 'active' flag");
    }
    List<String> amr = new ArrayList<>();
    for (JsonNode a : answer.path("amr")) {
      if (a.isString()) {
        amr.add(a.asString());
      }
    }
    return new FieldIntrospection(
        active.asBoolean(),
        text(answer, "sub"),
        text(answer, "clientId"),
        text(answer, "workerCode"),
        text(answer, "deviceId"),
        text(answer, "appCode"),
        amr,
        seconds(answer.get("authTime")),
        seconds(answer.get("exp")));
  }

  private static @Nullable String text(JsonNode node, String name) {
    JsonNode v = node.get(name);
    return v != null && v.isString() ? v.asString() : null;
  }

  /** The platform writes times as RFC 3339 text; unix seconds are accepted too. */
  private static @Nullable Instant seconds(@Nullable JsonNode v) {
    if (v == null) {
      return null;
    }
    if (v.isNumber()) {
      return Instant.ofEpochSecond(v.asLong());
    }
    if (v.isString()) {
      try {
        return java.time.OffsetDateTime.parse(v.asString()).toInstant();
      } catch (java.time.format.DateTimeParseException e) {
        return null;
      }
    }
    return null;
  }
}
