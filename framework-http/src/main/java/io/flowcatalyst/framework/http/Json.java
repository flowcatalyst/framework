package io.flowcatalyst.framework.http;

import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.exc.UnexpectedEndOfInputException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP module's Jackson 3 mapper: unknown properties fail, null for a primitive fails,
 * trailing content fails, duplicate keys fail, dates are ISO-8601, no default typing.
 */
public final class Json {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private Json() {}

  /**
   * The shared mapper. Thread-safe; do not reconfigure.
   *
   * @return the mapper
   */
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  /**
   * Turns a decode failure into a {@link ValidationException} with one {@code INVALID_JSON}
   * error. The message never echoes the offending value.
   *
   * @param e the decode failure
   * @return the exception to throw
   */
  static ValidationException toValidation(JacksonException e) {
    String message;
    if (e instanceof UnrecognizedPropertyException) {
      message = "unknown property";
    } else if (e instanceof InvalidNullException) {
      message = "must not be null";
    } else if (e instanceof UnexpectedEndOfInputException) {
      message = "unexpected end of input";
    } else if (e instanceof StreamReadException) {
      message = "malformed JSON";
    } else if (e instanceof MismatchedInputException) {
      message = "wrong type or format";
    } else {
      message = "invalid JSON";
    }
    return new ValidationException(List.of(new FieldError(pathOf(e), "INVALID_JSON", message)));
  }

  private static String pathOf(JacksonException e) {
    StringBuilder sb = new StringBuilder();
    for (JacksonException.Reference ref : e.getPath()) {
      String name = ref.getPropertyName();
      if (name != null) {
        if (!sb.isEmpty()) {
          sb.append('.');
        }
        sb.append(name);
      } else if (ref.getIndex() >= 0) {
        sb.append('[').append(ref.getIndex()).append(']');
      }
    }
    return sb.isEmpty() ? "body" : sb.toString();
  }
}
