package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import java.util.function.Function;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * The context id types as plain JSON strings, for the typed-operation mapper: the OpenAPI document
 * describes them as strings (contract section 5.1, "a framework value type"), so Jackson must write
 * them as strings, not as {@code {"value": ...}}.
 */
final class ContextIdsModule {

  private ContextIdsModule() {}

  static SimpleModule create() {
    SimpleModule m = new SimpleModule("fw-context-ids");
    add(m, ClientId.class, ClientId::value, ClientId::new);
    add(m, PrincipalId.class, PrincipalId::value, PrincipalId::new);
    add(m, ApplicationCode.class, ApplicationCode::value, ApplicationCode::new);
    add(m, CorrelationId.class, CorrelationId::value, CorrelationId::new);
    return m;
  }

  private static <T> void add(SimpleModule m, Class<T> type, Function<T, String> write, Function<String, T> read) {
    m.addSerializer(
        type,
        new ValueSerializer<T>() {
          @Override
          public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(write.apply(value));
          }
        });
    m.addDeserializer(
        type,
        new ValueDeserializer<T>() {
          @Override
          public T deserialize(JsonParser p, DeserializationContext ctxt) {
            if (!p.hasToken(JsonToken.VALUE_STRING)) {
              return type.cast(ctxt.handleUnexpectedToken(type, p));
            }
            String text = p.getString();
            try {
              return read.apply(text);
            } catch (IllegalArgumentException e) {
              throw ctxt.weirdStringException(text, type, "not a valid " + type.getSimpleName());
            }
          }
        });
  }
}
