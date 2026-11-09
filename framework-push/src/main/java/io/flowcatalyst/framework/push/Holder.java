package io.flowcatalyst.framework.push;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Names the streams to end ({@link Pushes#endStreams}): a stream ends when its principal, its
 * field device or its field login family equals a field that is not empty.
 *
 * @param principal the principal id, or {@code ""}
 * @param device the field device id, or {@code ""}
 * @param session the field login family, or {@code ""}
 */
public record Holder(String principal, String device, String session) {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /**
   * A holder from the three fields; {@code null} is empty.
   *
   * @param principal the principal id
   * @param device the device id
   * @param session the login family
   * @return the holder
   */
  public static Holder of(String principal, String device, String session) {
    return new Holder(principal, device, session);
  }

  /**
   * The streams of one principal.
   *
   * @param principal the principal id
   * @return the holder
   */
  public static Holder ofPrincipal(String principal) {
    return new Holder(principal, "", "");
  }

  /**
   * Whether the holder names nobody.
   *
   * @return true when all three fields are empty
   */
  public boolean isEmpty() {
    return principal.isEmpty() && device.isEmpty() && session.isEmpty();
  }

  /**
   * The JSON of the {@code fw_push_end} notification, the same as the Go framework writes: only the
   * fields that are not empty, as {@code principal}, {@code device} and {@code session}.
   *
   * @return the JSON text
   */
  public String toJson() {
    List<String> parts = new ArrayList<>(3);
    if (!principal.isEmpty()) {
      parts.add("\"principal\":" + quote(principal));
    }
    if (!device.isEmpty()) {
      parts.add("\"device\":" + quote(device));
    }
    if (!session.isEmpty()) {
      parts.add("\"session\":" + quote(session));
    }
    return "{" + String.join(",", parts) + "}";
  }

  /**
   * Reads the notification payload.
   *
   * @param json the payload
   * @return the holder; empty when the payload is not a holder
   */
  public static Holder fromJson(String json) {
    try {
      JsonNode node = MAPPER.readTree(json);
      if (node == null || !node.isObject()) {
        return new Holder("", "", "");
      }
      return new Holder(text(node, "principal"), text(node, "device"), text(node, "session"));
    } catch (RuntimeException e) {
      return new Holder("", "", "");
    }
  }

  private static String text(JsonNode node, String name) {
    JsonNode v = node.get(name);
    return v != null && v.isString() ? v.asString() : "";
  }

  private static String quote(String s) {
    return MAPPER.writeValueAsString(s);
  }
}
