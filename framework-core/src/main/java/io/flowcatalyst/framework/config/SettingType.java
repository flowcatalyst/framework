package io.flowcatalyst.framework.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The type of a setting: how raw source text (or a YAML scalar or list) becomes a typed value.
 *
 * @param <T> the Java type of parsed values
 */
public sealed interface SettingType<T> {

  /** Text, as written. */
  StringType STRING = new StringType();

  /** A 32-bit integer. */
  IntegerType INTEGER = new IntegerType();

  /** A 64-bit integer. */
  LongType LONG = new LongType();

  /** {@code true} or {@code false}, nothing else. */
  BooleanType BOOLEAN = new BooleanType();

  /** A duration such as {@code 500ms}, {@code 30s}, {@code 5m}, {@code 2h}, {@code 1d}, or ISO-8601. */
  DurationType DURATION = new DurationType();

  /** A URI. */
  UriType URI = new UriType();

  /** A list of strings: a YAML list, or comma-separated text. */
  StringListType STRING_LIST = new StringListType();

  /** A secret string. */
  SecretType SECRET = new SecretType();

  /**
   * Describes the accepted shape, for error messages and tooling.
   *
   * @return for example "an integer"
   */
  String describe();

  /**
   * Whether values of this type are secrets (never printed, never part of an error message).
   *
   * @return true for the secret type
   */
  default boolean isSecret() {
    return false;
  }

  /**
   * Parses a raw source value.
   *
   * @param raw text from an environment variable, or a scalar or list from YAML
   * @return the typed value
   * @throws ValueFormatException when the value does not have the expected shape
   */
  T parse(Object raw) throws ValueFormatException;

  /**
   * Narrows an already parsed value to this type's Java type.
   *
   * @param parsed a value previously returned by {@link #parse}
   * @return the same value, typed
   */
  T cast(Object parsed);

  /** Text. */
  record StringType() implements SettingType<String> {
    @Override
    public String describe() {
      return "a string";
    }

    @Override
    public String parse(Object raw) throws ValueFormatException {
      return Parsing.scalarText(raw, describe());
    }

    @Override
    public String cast(Object parsed) {
      return (String) parsed;
    }
  }

  /** A 32-bit integer. */
  record IntegerType() implements SettingType<Integer> {
    @Override
    public String describe() {
      return "an integer";
    }

    @Override
    public Integer parse(Object raw) throws ValueFormatException {
      try {
        return Integer.valueOf(Parsing.scalarText(raw, describe()).strip());
      } catch (NumberFormatException e) {
        throw new ValueFormatException(describe());
      }
    }

    @Override
    public Integer cast(Object parsed) {
      return (Integer) parsed;
    }
  }

  /** A 64-bit integer. */
  record LongType() implements SettingType<Long> {
    @Override
    public String describe() {
      return "a long integer";
    }

    @Override
    public Long parse(Object raw) throws ValueFormatException {
      try {
        return Long.valueOf(Parsing.scalarText(raw, describe()).strip());
      } catch (NumberFormatException e) {
        throw new ValueFormatException(describe());
      }
    }

    @Override
    public Long cast(Object parsed) {
      return (Long) parsed;
    }
  }

  /** A boolean. */
  record BooleanType() implements SettingType<Boolean> {
    @Override
    public String describe() {
      return "true or false";
    }

    @Override
    public Boolean parse(Object raw) throws ValueFormatException {
      String text = Parsing.scalarText(raw, describe()).strip();
      if (text.equals("true")) {
        return Boolean.TRUE;
      }
      if (text.equals("false")) {
        return Boolean.FALSE;
      }
      throw new ValueFormatException(describe());
    }

    @Override
    public Boolean cast(Object parsed) {
      return (Boolean) parsed;
    }
  }

  /** A duration. */
  record DurationType() implements SettingType<Duration> {
    private static final Pattern SIMPLE = Pattern.compile("(\\d{1,15})(ms|s|m|h|d)");

    @Override
    public String describe() {
      return "a duration such as 500ms, 30s, 5m, 2h, 1d or PT30S";
    }

    @Override
    public Duration parse(Object raw) throws ValueFormatException {
      String text = Parsing.scalarText(raw, describe()).strip();
      Matcher m = SIMPLE.matcher(text);
      if (m.matches()) {
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
          case "ms" -> Duration.ofMillis(n);
          case "s" -> Duration.ofSeconds(n);
          case "m" -> Duration.ofMinutes(n);
          case "h" -> Duration.ofHours(n);
          default -> Duration.ofDays(n);
        };
      }
      try {
        Duration d = Duration.parse(text);
        if (d.isNegative()) {
          throw new ValueFormatException(describe());
        }
        return d;
      } catch (DateTimeParseException e) {
        throw new ValueFormatException(describe());
      }
    }

    @Override
    public Duration cast(Object parsed) {
      return (Duration) parsed;
    }
  }

  /** A URI. */
  record UriType() implements SettingType<URI> {
    @Override
    public String describe() {
      return "a URI";
    }

    @Override
    public URI parse(Object raw) throws ValueFormatException {
      try {
        return new URI(Parsing.scalarText(raw, describe()).strip());
      } catch (URISyntaxException e) {
        throw new ValueFormatException(describe());
      }
    }

    @Override
    public URI cast(Object parsed) {
      return (URI) parsed;
    }
  }

  /**
   * One member of an enum, matched ignoring case and with {@code -} read as {@code _}.
   *
   * @param <E> the enum type
   * @param values the enum's constants, for example {@code Mode.values()}
   */
  record EnumType<E extends Enum<E>>(List<E> values) implements SettingType<E> {
    /** Copies the constants. */
    public EnumType {
      values = List.copyOf(values);
    }

    @Override
    public String describe() {
      List<String> names = new ArrayList<>();
      for (E v : values) {
        names.add(v.name());
      }
      return "one of " + String.join(", ", names);
    }

    @Override
    public E parse(Object raw) throws ValueFormatException {
      String text = Parsing.scalarText(raw, describe()).strip().replace('-', '_');
      for (E v : values) {
        if (v.name().equalsIgnoreCase(text)) {
          return v;
        }
      }
      throw new ValueFormatException(describe());
    }

    @Override
    public E cast(Object parsed) {
      for (E v : values) {
        if (v == parsed) {
          return v;
        }
      }
      throw new ClassCastException("not a member of this enum: " + parsed);
    }
  }

  /** A list of strings. */
  record StringListType() implements SettingType<List<String>> {
    @Override
    public String describe() {
      return "a list of strings (YAML list, or comma-separated text)";
    }

    @Override
    public List<String> parse(Object raw) throws ValueFormatException {
      List<String> out = new ArrayList<>();
      if (raw instanceof List<?> list) {
        for (Object item : list) {
          out.add(Parsing.scalarText(item, describe()));
        }
      } else {
        String text = Parsing.scalarText(raw, describe()).strip();
        if (!text.isEmpty()) {
          for (String part : text.split(",", -1)) {
            out.add(part.strip());
          }
        }
      }
      return List.copyOf(out);
    }

    @Override
    public List<String> cast(Object parsed) {
      List<String> out = new ArrayList<>();
      for (Object item : (List<?>) parsed) {
        out.add((String) item);
      }
      return List.copyOf(out);
    }
  }

  /** A secret string. */
  record SecretType() implements SettingType<Secret> {
    @Override
    public String describe() {
      return "a string";
    }

    @Override
    public boolean isSecret() {
      return true;
    }

    @Override
    public Secret parse(Object raw) throws ValueFormatException {
      return Secret.of(Parsing.scalarText(raw, describe()));
    }

    @Override
    public Secret cast(Object parsed) {
      return (Secret) parsed;
    }
  }
}
