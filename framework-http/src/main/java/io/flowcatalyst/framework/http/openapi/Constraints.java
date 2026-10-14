package io.flowcatalyst.framework.http.openapi;

import io.flowcatalyst.framework.http.Constraint;
import io.flowcatalyst.framework.http.Validator;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The constraints of the request bodies' validators, by record type and component (contract
 * section 5.4), and how they become schema keywords.
 */
final class Constraints {

  /** Constraints that change the document, per component of a type, and the validator they came from. */
  private record Entry(Validator<?> validator, Map<String, List<Constraint>> fields, String signature) {}

  private final Map<Class<?>, Entry> byType = new HashMap<>();
  private final Map<Validator<?>, Boolean> seen = new IdentityHashMap<>();
  private final List<String> problems;

  Constraints(List<String> problems) {
    this.problems = problems;
  }

  /** Collects a body validator and every validator nested in it. */
  void collect(Validator<?> validator, String path) {
    if (seen.put(validator, Boolean.TRUE) != null) {
      return;
    }
    Class<?> type = validator.type();
    Map<String, List<Constraint>> fields = new LinkedHashMap<>();
    Set<String> components = componentNames(type);
    for (Validator.FieldRules f : validator.fields()) {
      if (!components.contains(f.name())) {
        problems.add(
            path + ": the validator of " + type.getSimpleName() + " checks '" + f.name()
                + "', which is not a component of the record");
        continue;
      }
      for (Constraint c : f.constraints()) {
        nested(c, path + "." + f.name());
        if (documents(c)) {
          fields.computeIfAbsent(f.name(), k -> new ArrayList<>()).add(c);
        }
      }
    }
    String signature = signature(fields);
    Entry existing = byType.get(type);
    if (existing == null) {
      byType.put(type, new Entry(validator, fields, signature));
    } else if (!existing.signature().equals(signature)) {
      problems.add(
          path + ": two validators give " + type.getSimpleName() + " different constraints; a type has one"
              + " component, so give each its own body type or share the validator");
    }
  }

  private void nested(Constraint c, String path) {
    switch (c) {
      case Constraint.Valid v -> collect(v.validator(), path);
      case Constraint.Each e -> nested(e.element(), path + "[]");
      default -> {}
    }
  }

  private static boolean documents(Constraint c) {
    return switch (c) {
      case Constraint.None n -> false;
      case Constraint.NotNull n -> false;
      case Constraint.Valid v -> false;
      case Constraint.Each e -> documents(e.element());
      default -> true;
    };
  }

  private static String signature(Map<String, List<Constraint>> fields) {
    StringBuilder sb = new StringBuilder();
    fields.forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
    return sb.toString();
  }

  private static Set<String> componentNames(Class<?> type) {
    Set<String> names = new java.util.HashSet<>();
    if (type.isRecord()) {
      for (var rc : type.getRecordComponents()) {
        names.add(rc.getName());
      }
    }
    return names;
  }

  /** The documented constraints of one component, or none. */
  List<Constraint> of(Class<?> type, String component) {
    Entry e = byType.get(type);
    if (e == null) {
      return List.of();
    }
    return e.fields().getOrDefault(component, List.of());
  }

  /**
   * Applies constraints to a property's schema (a mutable map), merging as the contract says:
   * the tighter bound wins; an explicit pattern wins over {@code notBlank}'s.
   */
  void apply(Map<String, Object> schema, List<Constraint> constraints, String path) {
    boolean explicitPattern = false;
    for (Constraint c : constraints) {
      if (c instanceof Constraint.Pattern) {
        explicitPattern = true;
      }
    }
    for (Constraint c : constraints) {
      applyOne(schema, c, path, explicitPattern);
    }
  }

  @SuppressWarnings("unchecked")
  private void applyOne(Map<String, Object> schema, Constraint c, String path, boolean explicitPattern) {
    String kind = kind(schema);
    switch (c) {
      case Constraint.NotBlank n -> {
        if (!requireKind(kind, "string", "notBlank", path)) {
          return;
        }
        tighten(schema, "minLength", 1L, true);
        if (!explicitPattern) {
          setPattern(schema, "\\S", path);
        }
      }
      case Constraint.MinLength m -> {
        if (requireKind(kind, "string", "minLength", path)) {
          tighten(schema, "minLength", (long) m.min(), true);
        }
      }
      case Constraint.MaxLength m -> {
        if (requireKind(kind, "string", "maxLength", path)) {
          tighten(schema, "maxLength", (long) m.max(), false);
        }
      }
      case Constraint.Pattern p -> {
        if (!requireKind(kind, "string", "pattern", path)) {
          return;
        }
        String problem = EcmaRegex.problem(p.regex(), p.flags());
        if (problem != null) {
          problems.add(path + ": the pattern " + p.regex() + " cannot be documented: " + problem + "; use Rules.custom");
          return;
        }
        setPattern(schema, "^(?:" + p.regex() + ")$", path);
      }
      case Constraint.Range r -> {
        if (!kind.equals("integer") && !kind.equals("number")) {
          problems.add(path + ": range applies to a number, not a " + kind);
          return;
        }
        tighten(schema, "minimum", number(r.min()), true);
        tighten(schema, "maximum", number(r.max()), false);
      }
      case Constraint.Size s -> {
        if (requireKind(kind, "array", "size", path)) {
          tighten(schema, "minItems", (long) s.min(), true);
          tighten(schema, "maxItems", (long) s.max(), false);
        }
      }
      case Constraint.OneOf o -> oneOf(schema, o, kind, path);
      case Constraint.Each e -> {
        if (!requireKind(kind, "array", "each", path)) {
          return;
        }
        Object items = schema.get("items");
        if (items instanceof Map<?, ?> m) {
          Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) m);
          applyOne(copy, e.element(), path + "[]", e.element() instanceof Constraint.Pattern);
          schema.put("items", copy);
        }
      }
      default -> {}
    }
  }

  private void oneOf(Map<String, Object> schema, Constraint.OneOf o, String kind, String path) {
    List<Object> values = new ArrayList<>();
    for (Object v : o.values()) {
      @Nullable Object json = switch (v) {
        case Enum<?> e -> e.name();
        case String s -> s;
        case Boolean b -> b;
        case Integer i -> (long) i;
        case Long l -> l;
        case BigDecimal d -> d;
        case Number n -> new BigDecimal(n.toString());
        default -> null;
      };
      if (json == null) {
        problems.add(path + ": oneOf values must be strings, numbers, booleans or enum constants");
        return;
      }
      values.add(json);
    }
    if (kind.equals("ref") || kind.equals("array") || kind.equals("object") || kind.equals("any")) {
      problems.add(path + ": oneOf applies to a single value, not a " + kind);
      return;
    }
    Object existing = schema.get("enum");
    if (existing instanceof List<?> allowed) {
      for (Object v : values) {
        if (!allowed.contains(v)) {
          problems.add(path + ": oneOf names " + v + ", which is not a constant of the enum");
          return;
        }
      }
    }
    schema.put("enum", List.copyOf(values));
  }

  private boolean requireKind(String kind, String wanted, String rule, String path) {
    if (!kind.equals(wanted)) {
      problems.add(path + ": " + rule + " applies to a" + (wanted.equals("array") ? "n " : " ") + wanted + ", not a " + kind);
      return false;
    }
    return true;
  }

  private void setPattern(Map<String, Object> schema, String pattern, String path) {
    Object existing = schema.get("pattern");
    if (existing != null && !existing.equals(pattern)) {
      problems.add(path + ": two patterns apply to one value (" + existing + " and " + pattern + ")");
      return;
    }
    schema.put("pattern", pattern);
  }

  /** Keeps the larger lower bound or the smaller upper bound. */
  private static void tighten(Map<String, Object> schema, String key, Object value, boolean lower) {
    Object existing = schema.get(key);
    if (existing == null) {
      schema.put(key, value);
      return;
    }
    BigDecimal a = decimal(existing);
    BigDecimal b = decimal(value);
    boolean replace = lower ? b.compareTo(a) > 0 : b.compareTo(a) < 0;
    if (replace) {
      schema.put(key, value);
    }
  }

  private static BigDecimal decimal(Object v) {
    return switch (v) {
      case BigDecimal d -> d;
      case BigInteger i -> new BigDecimal(i);
      case Long l -> BigDecimal.valueOf(l);
      default -> new BigDecimal(Objects.toString(v));
    };
  }

  /** A whole number as an integer, anything else as a decimal. */
  static Object number(BigDecimal d) {
    BigDecimal s = d.stripTrailingZeros();
    if (s.scale() <= 0) {
      return s.toBigIntegerExact();
    }
    return s;
  }

  /** The kind of a schema: its {@code type}, {@code ref} for a reference or a union, or {@code any}. */
  static String kind(Map<String, Object> schema) {
    if (schema.containsKey("$ref") || schema.containsKey("oneOf")) {
      return "ref";
    }
    Object type = schema.get("type");
    return type instanceof String t ? t : "any";
  }
}
