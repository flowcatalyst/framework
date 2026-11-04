package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.measure.Decimals;
import io.flowcatalyst.framework.measure.MeasureException;
import io.flowcatalyst.framework.measure.money.Currency;
import io.flowcatalyst.framework.measure.money.Money;
import io.flowcatalyst.framework.measure.units.Quantity;
import io.flowcatalyst.framework.measure.units.Unit;
import io.flowcatalyst.framework.measure.units.UnitPrice;
import java.math.BigDecimal;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.module.SimpleModule;

/**
 * The measure types on the wire, for the typed-operation mapper (measure contract section 5): {@code
 * Money} as {@code {"amount": "3132.48", "currency": "ZAR"}}, {@code Quantity} as {@code {"value":
 * "7831.2", "unit": "KGM"}}, {@code UnitPrice} as {@code {"price": {...}, "per": "KGM"}}, {@code
 * Currency} and {@code Unit} as their code. Amounts and values are always JSON strings, with their
 * scale. Reading applies the constructors' validation: a failure carries the contract's error code,
 * which the operation's decoder reports (see {@code Json.toValidation}).
 */
final class MeasureModule {

  private MeasureModule() {}

  static SimpleModule create() {
    SimpleModule m = new SimpleModule("fw-measure");
    scalar(m, Currency.class, Currency::code, Currency::of);
    scalar(m, Unit.class, Unit::code, Unit::of);
    m.addSerializer(
        Money.class,
        new ValueSerializer<Money>() {
          @Override
          public void serialize(Money value, JsonGenerator gen, SerializationContext ctxt) {
            writeMoney(value, gen);
          }
        });
    m.addSerializer(
        Quantity.class,
        new ValueSerializer<Quantity>() {
          @Override
          public void serialize(Quantity value, JsonGenerator gen, SerializationContext ctxt) {
            writeQuantity(value, gen);
          }
        });
    m.addSerializer(
        UnitPrice.class,
        new ValueSerializer<UnitPrice>() {
          @Override
          public void serialize(UnitPrice value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeStartObject();
            gen.writeName("price");
            writeMoney(value.price(), gen);
            gen.writeStringProperty("per", value.per().code());
            gen.writeEndObject();
          }
        });
    m.addDeserializer(
        Money.class,
        new ValueDeserializer<Money>() {
          @Override
          public Money deserialize(JsonParser p, DeserializationContext ctxt) {
            return readMoney(p, ctxt.readTree(p));
          }
        });
    m.addDeserializer(
        Quantity.class,
        new ValueDeserializer<Quantity>() {
          @Override
          public Quantity deserialize(JsonParser p, DeserializationContext ctxt) {
            return readQuantity(p, ctxt.readTree(p));
          }
        });
    m.addDeserializer(
        UnitPrice.class,
        new ValueDeserializer<UnitPrice>() {
          @Override
          public UnitPrice deserialize(JsonParser p, DeserializationContext ctxt) {
            JsonNode n = ctxt.readTree(p);
            object(p, n, Set.of("price", "per"), UnitPrice.class);
            try {
              return new UnitPrice(readMoney(p, n.get("price")), Unit.of(string(p, n, "per", UnitPrice.class)));
            } catch (MeasureException e) {
              throw DatabindException.from(p, e.getMessage(), e);
            }
          }
        });
    return m;
  }

  private static void writeMoney(Money value, JsonGenerator gen) {
    gen.writeStartObject();
    gen.writeStringProperty("amount", Decimals.format(value.amount()));
    gen.writeStringProperty("currency", value.currency().code());
    gen.writeEndObject();
  }

  private static void writeQuantity(Quantity value, JsonGenerator gen) {
    gen.writeStartObject();
    gen.writeStringProperty("value", Decimals.format(value.value()));
    gen.writeStringProperty("unit", value.unit().code());
    gen.writeEndObject();
  }

  private static Money readMoney(JsonParser p, JsonNode n) {
    object(p, n, Set.of("amount", "currency"), Money.class);
    try {
      BigDecimal amount = Decimals.parse(string(p, n, "amount", Money.class));
      return new Money(amount, Currency.of(string(p, n, "currency", Money.class)));
    } catch (MeasureException e) {
      throw DatabindException.from(p, e.getMessage(), e);
    }
  }

  private static Quantity readQuantity(JsonParser p, JsonNode n) {
    object(p, n, Set.of("value", "unit"), Quantity.class);
    try {
      return new Quantity(Decimals.parse(string(p, n, "value", Quantity.class)), Unit.of(string(p, n, "unit", Quantity.class)));
    } catch (MeasureException e) {
      throw DatabindException.from(p, e.getMessage(), e);
    }
  }

  /** The node must be an object with exactly these members. */
  private static void object(JsonParser p, JsonNode n, Set<String> members, Class<?> type) {
    if (!n.isObject()) {
      throw MismatchedInputException.from(p, type, "not an object of " + members);
    }
    for (String name : n.propertyNames()) {
      if (!members.contains(name)) {
        throw MismatchedInputException.from(p, type, "unknown property " + name);
      }
    }
    for (String name : members) {
      if (!n.has(name)) {
        throw MismatchedInputException.from(p, type, "missing property " + name);
      }
    }
  }

  /** A member that is a JSON string: amounts and values are never JSON numbers. */
  private static String string(JsonParser p, JsonNode n, String name, Class<?> type) {
    JsonNode v = n.get(name);
    if (v == null || !v.isString()) {
      throw MismatchedInputException.from(p, type, name + " must be a JSON string");
    }
    return v.asString();
  }

  private static <T> void scalar(SimpleModule m, Class<T> type, Function<T, String> write, Function<String, T> read) {
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
            try {
              return read.apply(p.getString());
            } catch (MeasureException e) {
              throw DatabindException.from(p, e.getMessage(), e);
            }
          }
        });
  }
}
