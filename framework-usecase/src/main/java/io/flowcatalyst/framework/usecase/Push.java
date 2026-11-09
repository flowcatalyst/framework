package io.flowcatalyst.framework.usecase;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A push to clients (push contract, section 1): a topic, a type and the data as compact JSON text. A
 * push is a small signal for the browsers, apps and devices listening on its topic ("order 7
 * changed"), written in a transaction with {@link Plan#andPush} (or {@code Pushes.publish} outside
 * a use case) and sent only if that transaction commits.
 *
 * <p>The checks are the contract's: the topic is {@code {application}:{segment}(:{segment})*}, the
 * type is {@code [a-z][a-z0-9-]*(.[a-z][a-z0-9-]*)*} and not reserved, the data is a JSON object of
 * at most {@link #MAX_DATA_BYTES} bytes of UTF-8 (the configured {@code push.max-data-bytes} is
 * checked when the push is written). A push that breaks them is not built:
 * {@link InvalidPushException}.
 *
 * @param topic the topic
 * @param type the type
 * @param data the data, compact JSON text of an object
 */
public record Push(String topic, String type, String data) {

  /** The longest topic, in characters. */
  public static final int MAX_TOPIC_LENGTH = 255;

  /** The longest type, in characters. */
  public static final int MAX_TYPE_LENGTH = 100;

  /** The most {@code push.max-data-bytes} may be. */
  public static final int MAX_DATA_BYTES = 65_536;

  /** The default {@code push.max-data-bytes}. */
  public static final int DEFAULT_MAX_DATA_BYTES = 16_384;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private static final JsonFactory STRICT =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  /**
   * Checks the three parts; the data is compacted (whitespace outside strings removed).
   *
   * @throws InvalidPushException when the topic, type or data breaks the contract
   */
  public Push {
    Objects.requireNonNull(topic, "topic");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(data, "data");
    checkTopic(topic);
    checkType(type);
    data = compact(data, MAX_DATA_BYTES);
  }

  /**
   * A push whose data is serialized from an object: a map (keys sorted), a record, or a Jackson
   * tree. It must serialize to a JSON object.
   *
   * @param topic the topic
   * @param type the type
   * @param data what to send
   * @return the push
   * @throws InvalidPushException when the topic, type or data breaks the contract
   */
  public static Push of(String topic, String type, Object data) {
    String text;
    try {
      text = MAPPER.writeValueAsString(data);
    } catch (RuntimeException e) {
      throw new InvalidPushException(
          InvalidPushException.DATA_INVALID, "the data does not serialize to JSON: " + e.getMessage());
    }
    return new Push(topic, type, text);
  }

  /**
   * A push whose data is JSON text, kept as written apart from whitespace outside strings.
   *
   * @param topic the topic
   * @param type the type
   * @param json the data, a JSON object
   * @return the push
   * @throws InvalidPushException when the topic, type or data breaks the contract
   */
  public static Push ofJson(String topic, String type, String json) {
    return new Push(topic, type, json);
  }

  /**
   * Checks a topic: {@code {application}:{segment}(:{segment})*}, the application
   * {@code [a-z][a-z0-9-]*}, each further segment {@code [0-9A-Za-z_.-]+}, at least two segments, at
   * most 255 characters.
   *
   * @param topic the topic
   * @throws InvalidPushException {@code TOPIC_INVALID}
   */
  public static void checkTopic(String topic) {
    if (topic.length() > MAX_TOPIC_LENGTH) {
      throw refuse(InvalidPushException.TOPIC_INVALID, "a topic is at most " + MAX_TOPIC_LENGTH + " characters");
    }
    String[] segments = topic.split(":", -1);
    if (segments.length < 2 || !isApplication(segments[0])) {
      throw refuse(InvalidPushException.TOPIC_INVALID, "a topic is {application}:{segment}(:{segment})*: " + topic);
    }
    for (int i = 1; i < segments.length; i++) {
      if (!isSegment(segments[i])) {
        throw refuse(InvalidPushException.TOPIC_INVALID, "a topic segment is [0-9A-Za-z_.-]+: " + topic);
      }
    }
  }

  /**
   * Checks a type: {@code [a-z][a-z0-9-]*(.[a-z][a-z0-9-]*)*}, at most 100 characters, not
   * {@code reset}, {@code auth-expired} or {@code subscribed} and not starting {@code fw.}.
   *
   * @param type the type
   * @throws InvalidPushException {@code TYPE_INVALID} or {@code TYPE_RESERVED}
   */
  public static void checkType(String type) {
    if (type.length() > MAX_TYPE_LENGTH) {
      throw refuse(InvalidPushException.TYPE_INVALID, "a type is at most " + MAX_TYPE_LENGTH + " characters");
    }
    for (String part : type.split("\\.", -1)) {
      if (!isApplication(part)) {
        throw refuse(
            InvalidPushException.TYPE_INVALID, "a type is [a-z][a-z0-9-]*(.[a-z][a-z0-9-]*)*: " + type);
      }
    }
    if (type.equals("reset") || type.equals("auth-expired") || type.equals("subscribed") || type.startsWith("fw.")) {
      throw refuse(InvalidPushException.TYPE_RESERVED, "the type '" + type + "' is reserved");
    }
  }

  /**
   * Whether the text is an application code: {@code [a-z][a-z0-9-]*}.
   *
   * @param s the text
   * @return true when it is one
   */
  public static boolean isApplication(String s) {
    if (s.isEmpty() || s.charAt(0) < 'a' || s.charAt(0) > 'z') {
      return false;
    }
    for (int i = 1; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-') {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether the text is a topic segment after the first: {@code [0-9A-Za-z_.-]+}.
   *
   * @param s the text
   * @return true when it is one
   */
  public static boolean isSegment(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks JSON text as the data of a push and returns it compact: a JSON object (duplicate names
   * refused), whitespace outside strings removed, at most {@code limit} bytes of UTF-8.
   *
   * @param json the text
   * @param limit the most bytes
   * @return the compact text
   * @throws InvalidPushException {@code DATA_INVALID}, {@code DATA_NOT_OBJECT} or {@code DATA_TOO_LARGE}
   */
  public static String compact(String json, int limit) {
    try (JsonParser parser = STRICT.createParser(ObjectReadContext.empty(), json)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        throw refuse(InvalidPushException.DATA_INVALID, "the data is not valid JSON");
      }
      parser.skipChildren();
      if (parser.nextToken() != null) {
        throw refuse(InvalidPushException.DATA_INVALID, "the data is not valid JSON");
      }
      if (first != JsonToken.START_OBJECT) {
        throw refuse(InvalidPushException.DATA_NOT_OBJECT, "the data is not a JSON object");
      }
    } catch (StreamReadException e) {
      throw refuse(InvalidPushException.DATA_INVALID, "the data is not valid JSON");
    }
    String text = stripWhitespace(json);
    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > limit) {
      throw refuse(InvalidPushException.DATA_TOO_LARGE, "the data is " + bytes + " bytes, more than " + limit);
    }
    return text;
  }

  private static String stripWhitespace(String json) {
    StringBuilder out = new StringBuilder(json.length());
    boolean inString = false;
    boolean escaped = false;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (inString) {
        out.append(c);
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
        out.append(c);
      } else if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static InvalidPushException refuse(String reason, String message) {
    return new InvalidPushException(reason, message);
  }
}
