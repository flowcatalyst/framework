package io.flowcatalyst.framework.http.openapi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.CorrelationId;
import io.flowcatalyst.framework.context.PrincipalId;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Walks the types reachable from the operations once, at the JSON edge (contract section 5): the
 * type table, records as components, sealed interfaces as {@code oneOf} with a discriminator,
 * generic records named by their arguments, every refusal with the path from the operation.
 * Reading record components and their generic types is reflection, as Jackson's; it never scans the
 * classpath.
 */
final class TypeWalker {

  static final String REF = "#/components/schemas/";

  /** A resolved type: a class and its resolved type arguments. */
  record JType(Class<?> raw, List<JType> args) {
    static JType of(Class<?> raw) {
      return new JType(raw, List.of());
    }
  }

  private final Map<Class<?>, Schema> registered;
  private final Map<Class<?>, String> names;
  private final Map<Class<?>, Map<String, Schema>> properties;
  private final Constraints constraints;
  private final List<String> problems;

  /** Component bodies by name, sorted by UTF-8 bytes. */
  final Map<String, Map<String, Object>> components = new TreeMap<>(Emitter.UTF8_ORDER);

  private final Map<JType, String> typeNames = new HashMap<>();
  private final Map<String, JType> nameOwners = new HashMap<>();

  TypeWalker(
      Map<Class<?>, Schema> registered,
      Map<Class<?>, String> names,
      Map<Class<?>, Map<String, Schema>> properties,
      Constraints constraints,
      List<String> problems) {
    this.registered = registered;
    this.names = names;
    this.properties = properties;
    this.constraints = constraints;
    this.problems = problems;
  }

  /** Reserves a component name for the framework (the error envelope). */
  void reserve(String name, Map<String, Object> body) {
    nameOwners.put(name, JType.of(Void.class));
    components.put(name, body);
  }

  // ---- entry points ----------------------------------------------------------------------------

  /** The schema of a declared type, for example an operation's body or response. */
  Map<String, Object> schema(Type type, String path) {
    JType t = resolve(type, Map.of(), path);
    return t == null ? new LinkedHashMap<>() : schemaOf(t, path);
  }

  // ---- types -----------------------------------------------------------------------------------

  private @Nullable JType resolve(Type type, Map<TypeVariable<?>, JType> bindings, String path) {
    switch (type) {
      case Class<?> c -> {
        if (c.getTypeParameters().length > 0) {
          problems.add(path + ": the raw type " + c.getSimpleName() + " has no type arguments");
          return null;
        }
        return JType.of(c);
      }
      case ParameterizedType p -> {
        Class<?> raw = (Class<?>) p.getRawType();
        List<JType> args = new ArrayList<>();
        for (Type a : p.getActualTypeArguments()) {
          JType r = resolve(a, bindings, path);
          if (r == null) {
            return null;
          }
          args.add(r);
        }
        return new JType(raw, List.copyOf(args));
      }
      case TypeVariable<?> v -> {
        JType bound = bindings.get(v);
        if (bound == null) {
          problems.add(path + ": the type variable " + v.getName() + " is not bound to a type");
        }
        return bound;
      }
      case WildcardType w -> {
        problems.add(path + ": the wildcard type " + w + " is refused; name the type");
        return null;
      }
      case GenericArrayType g -> {
        problems.add(path + ": the array type " + g + " is refused; use a List");
        return null;
      }
      default -> {
        problems.add(path + ": the type " + type + " is refused");
        return null;
      }
    }
  }

  /** A fresh schema map for a resolved type: inline or a {@code $ref}. */
  private Map<String, Object> schemaOf(JType t, String path) {
    Class<?> raw = t.raw();
    Schema reg = registered.get(raw);
    if (reg != null) {
      // a union or an object is a component under the type's name; a value type is written inline
      if (reg.keywords.containsKey("oneOf") || "object".equals(reg.keywords.get("type"))) {
        return component(t, path, () -> render(reg, path));
      }
      return render(reg, path);
    }
    Map<String, Object> builtIn = builtIn(raw);
    if (builtIn != null) {
      return builtIn;
    }
    if (raw.isEnum()) {
      for (String p : JacksonCheck.enumType(raw)) {
        problems.add(path + ": " + p);
      }
      List<Object> constants = new ArrayList<>();
      for (Object c : raw.getEnumConstants()) {
        constants.add(((Enum<?>) c).name());
      }
      Map<String, Object> m = typed("string");
      m.put("enum", List.copyOf(constants));
      return m;
    }
    if (raw == List.class || raw == Collection.class || raw == Set.class || raw == SortedSet.class) {
      Map<String, Object> m = typed("array");
      m.put("items", schemaOf(t.args().getFirst(), path + "[]"));
      if (raw == Set.class || raw == SortedSet.class) {
        m.put("uniqueItems", true);
      }
      return m;
    }
    if (raw == Map.class || raw == SortedMap.class) {
      if (t.args().getFirst().raw() != String.class) {
        problems.add(path + ": a Map's key must be a String, not " + t.args().getFirst().raw().getSimpleName());
        return new LinkedHashMap<>();
      }
      Map<String, Object> m = typed("object");
      m.put("additionalProperties", schemaOf(t.args().get(1), path + "{}"));
      return m;
    }
    if (raw == Optional.class) {
      problems.add(path + ": Optional is refused (house rule: never a field); make the component @Nullable");
      return new LinkedHashMap<>();
    }
    if (raw == Object.class) {
      problems.add(path + ": Object is refused; name the type");
      return new LinkedHashMap<>();
    }
    if (raw.isArray()) {
      problems.add(path + ": the array " + raw.getSimpleName() + " is refused; use a List (byte[] is the one array allowed)");
      return new LinkedHashMap<>();
    }
    if (raw.isRecord()) {
      return component(t, path, () -> recordBody(t, path));
    }
    if (isSealedInterface(raw)) {
      if (raw.getTypeParameters().length > 0) {
        problems.add(path + ": the generic sealed interface " + raw.getSimpleName() + " is refused");
        return new LinkedHashMap<>();
      }
      return component(t, path, () -> sealedBody(raw, path));
    }
    problems.add(
        path + ": " + raw.getName() + " is not a record, an enum, a sealed interface or a registered value"
            + " type (OpenApi.schema)");
    return new LinkedHashMap<>();
  }

  private static @Nullable Map<String, Object> builtIn(Class<?> raw) {
    if (raw == String.class) {
      return typed("string");
    }
    if (raw == boolean.class || raw == Boolean.class) {
      return typed("boolean");
    }
    if (raw == int.class || raw == Integer.class) {
      return formatted("integer", "int32");
    }
    if (raw == long.class || raw == Long.class) {
      return formatted("integer", "int64");
    }
    if (raw == double.class || raw == Double.class) {
      return formatted("number", "double");
    }
    if (raw == BigDecimal.class) {
      return formatted("string", "decimal");
    }
    if (raw == Instant.class) {
      return formatted("string", "date-time");
    }
    if (raw == LocalDate.class) {
      return formatted("string", "date");
    }
    if (raw == Duration.class) {
      return formatted("string", "duration");
    }
    if (raw == UUID.class) {
      return formatted("string", "uuid");
    }
    if (raw == byte[].class) {
      Map<String, Object> m = typed("string");
      m.put("contentEncoding", "base64");
      return m;
    }
    if (raw == JsonNode.class) {
      return new LinkedHashMap<>();
    }
    if (raw == ClientId.class) {
      Map<String, Object> m = typed("string");
      m.put("pattern", "^[A-Za-z0-9._-]{1,100}$");
      return m;
    }
    if (raw == ApplicationCode.class) {
      Map<String, Object> m = typed("string");
      m.put("pattern", "^[a-z][a-z0-9-]*$");
      return m;
    }
    if (raw == PrincipalId.class || raw == CorrelationId.class) {
      Map<String, Object> m = typed("string");
      m.put("pattern", "\\S");
      m.put("minLength", 1L);
      return m;
    }
    return null;
  }

  // ---- components ------------------------------------------------------------------------------

  private Map<String, Object> component(JType t, String path, Supplier<Map<String, Object>> body) {
    String known = typeNames.get(t);
    if (known != null) {
      return ref(known);
    }
    String name = nameOf(t, path);
    if (name == null) {
      return new LinkedHashMap<>();
    }
    JType owner = nameOwners.get(name);
    if (owner != null && !owner.equals(t)) {
      problems.add(
          path + ": two types produce the component name " + name + ": " + describe(owner) + " and " + describe(t)
              + " (rename one, or OpenApi.name(app, Type.class, \"OtherName\"))");
      return ref(name);
    }
    typeNames.put(t, name);
    nameOwners.put(name, t);
    components.put(name, new LinkedHashMap<>()); // a placeholder while a recursive type is walked
    components.put(name, body.get());
    return ref(name);
  }

  private static String describe(JType t) {
    if (t.raw() == Void.class) {
      return "the framework's error envelope";
    }
    return t.args().isEmpty() ? t.raw().getName() : t.raw().getName() + t.args().stream().map(TypeWalker::describe).toList();
  }

  private @Nullable String nameOf(JType t, String path) {
    Class<?> raw = t.raw();
    String base = names.get(raw);
    if (base == null) {
      if (raw.isAnonymousClass() || raw.isLocalClass()) {
        problems.add(path + ": a local or anonymous " + raw.getName() + " has no component name; make it a member type");
        return null;
      }
      Class<?> parent = raw.isRecord() ? variantParent(raw, path) : null;
      base = parent != null ? nameOf(JType.of(parent), path) + raw.getSimpleName() : nestedName(raw);
    }
    StringBuilder sb = new StringBuilder(base);
    for (JType a : t.args()) {
      sb.append(argName(a, path));
    }
    return sb.toString();
  }

  /**
   * The simple name prefixed by the enclosing records, interfaces and enums ({@code
   * IngestionView.Route} is {@code IngestionViewRoute}); an enclosing class that is none of those, a
   * holder of API types, adds nothing (MO).
   */
  private static String nestedName(Class<?> c) {
    StringBuilder sb = new StringBuilder(c.getSimpleName());
    for (Class<?> e = c.getEnclosingClass(); e != null; e = e.getEnclosingClass()) {
      if (e.isRecord() || e.isInterface() || e.isEnum()) {
        sb.insert(0, e.getSimpleName());
      }
    }
    return sb.toString();
  }

  private String argName(JType a, String path) {
    Class<?> raw = a.raw();
    if (raw.isRecord() || raw.isEnum() || isSealedInterface(raw)) {
      String n = raw.isEnum() ? names.getOrDefault(raw, nestedName(raw)) : nameOf(a, path);
      return n == null ? raw.getSimpleName() : n;
    }
    StringBuilder sb = new StringBuilder(raw == Collection.class || raw == SortedSet.class ? (raw == SortedSet.class ? "Set" : "List") : raw.getSimpleName());
    for (JType x : a.args()) {
      if (raw == Map.class || raw == SortedMap.class) {
        if (x == a.args().getFirst()) {
          continue;
        }
      }
      sb.append(argName(x, path));
    }
    return sb.toString();
  }

  private static Map<String, Object> ref(String name) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("$ref", REF + name);
    return m;
  }

  // ---- records ---------------------------------------------------------------------------------

  private Map<String, Object> recordBody(JType t, String path) {
    Class<?> raw = t.raw();
    for (String p : JacksonCheck.record(raw)) {
      problems.add(path + ": " + p);
    }
    Map<TypeVariable<?>, JType> bindings = new HashMap<>();
    TypeVariable<?>[] params = raw.getTypeParameters();
    for (int i = 0; i < params.length && i < t.args().size(); i++) {
      bindings.put(params[i], t.args().get(i));
    }
    Map<String, Object> props = new LinkedHashMap<>();
    List<Object> required = new ArrayList<>();
    Class<?> parent = variantParent(raw, path);
    String discriminator = null;
    if (parent != null) {
      JsonTypeInfo info = parent.getAnnotation(JsonTypeInfo.class);
      String variant = variantTypeName(parent, raw);
      if (info != null && variant != null) {
        discriminator = info.property();
        Map<String, Object> d = typed("string");
        d.put("const", variant);
        props.put(discriminator, d);
        required.add(discriminator);
      }
    }
    Map<String, Schema> overrides = properties.getOrDefault(raw, Map.of());
    List<String> componentNames = new ArrayList<>();
    for (RecordComponent rc : raw.getRecordComponents()) {
      String name = rc.getName();
      componentNames.add(name);
      String here = path + "." + name;
      if (name.equals(discriminator)) {
        problems.add(here + ": the component has the name of the discriminator property '" + name + "'");
        continue;
      }
      boolean nullable = isNullable(rc.getAnnotatedType());
      nestedNullable(rc.getAnnotatedType(), here, true);
      Schema override = overrides.get(name);
      Map<String, Object> s;
      if (override != null) {
        s = render(override, here);
      } else {
        JType ct = resolve(rc.getGenericType(), bindings, here);
        s = ct == null ? new LinkedHashMap<>() : schemaOf(ct, here);
        constraints.apply(s, constraints.of(raw, name), here);
      }
      props.put(name, s);
      if (!nullable) {
        required.add(name);
      }
    }
    for (String name : overrides.keySet()) {
      if (!componentNames.contains(name)) {
        problems.add(path + ": OpenApi.property names '" + name + "', which is not a component of " + raw.getSimpleName());
      }
    }
    Map<String, Object> body = typed("object");
    body.put("properties", props);
    if (!required.isEmpty()) {
      body.put("required", List.copyOf(required));
    }
    body.put("additionalProperties", false);
    return body;
  }

  private static boolean isNullable(AnnotatedType type) {
    return type.isAnnotationPresent(Nullable.class);
  }

  /** {@code @Nullable} inside a type argument or an array means nothing on the wire: refused. */
  private void nestedNullable(AnnotatedType type, String path, boolean top) {
    if (!top && isNullable(type)) {
      problems.add(path + ": @Nullable on a type argument is refused (arrays and maps hold no absent values)");
      return;
    }
    if (type instanceof AnnotatedParameterizedType p) {
      for (AnnotatedType a : p.getAnnotatedActualTypeArguments()) {
        nestedNullable(a, path, false);
      }
    } else if (type instanceof AnnotatedArrayType a) {
      nestedNullable(a.getAnnotatedGenericComponentType(), path, false);
    }
  }

  // ---- sealed interfaces -----------------------------------------------------------------------

  private static boolean isSealedInterface(Class<?> c) {
    return c.isInterface() && c.isSealed();
  }

  /** The sealed interface with {@code @JsonTypeInfo} this record is a variant of, if any. */
  private @Nullable Class<?> variantParent(Class<?> record, String path) {
    List<Class<?>> parents = new ArrayList<>();
    for (Class<?> i : record.getInterfaces()) {
      if (isSealedInterface(i) && i.isAnnotationPresent(JsonTypeInfo.class) && !registered.containsKey(i)) {
        parents.add(i);
      }
    }
    if (parents.size() > 1) {
      problems.add(path + ": " + record.getSimpleName() + " is a variant of two sealed interfaces with @JsonTypeInfo");
    }
    return parents.isEmpty() ? null : parents.getFirst();
  }

  private static @Nullable String variantTypeName(Class<?> parent, Class<?> variant) {
    JsonSubTypes subs = parent.getAnnotation(JsonSubTypes.class);
    if (subs == null) {
      return null;
    }
    for (JsonSubTypes.Type s : subs.value()) {
      if (s.value() == variant) {
        return s.name().isEmpty() ? null : s.name();
      }
    }
    return null;
  }

  private Map<String, Object> sealedBody(Class<?> iface, String here) {
    JsonTypeInfo info = iface.getAnnotation(JsonTypeInfo.class);
    JsonSubTypes subs = iface.getAnnotation(JsonSubTypes.class);
    for (String a : JacksonCheck.foreign(iface, Set.of(JacksonCheck.JSON_TYPE_INFO, JacksonCheck.JSON_SUB_TYPES))) {
      problems.add(here + ": Jackson annotation " + a + " on " + iface.getSimpleName() + " is not understood by the document");
    }
    for (String p : JacksonCheck.sealedMethods(iface)) {
      problems.add(here + ": " + p);
    }
    if (info == null || subs == null) {
      problems.add(
          here + ": the sealed interface " + iface.getSimpleName() + " needs @JsonTypeInfo(use = NAME, include ="
              + " PROPERTY, property = \"...\") and @JsonSubTypes naming its permitted subtypes, or a schema"
              + " registered with OpenApi.schema");
      return new LinkedHashMap<>();
    }
    boolean ok = true;
    if (info.use() != JsonTypeInfo.Id.NAME || info.include() != JsonTypeInfo.As.PROPERTY || info.property().isEmpty()) {
      problems.add(
          here + ": @JsonTypeInfo on " + iface.getSimpleName() + " must be (use = NAME, include = PROPERTY,"
              + " property = \"...\")");
      ok = false;
    }
    if (info.defaultImpl() != JsonTypeInfo.class || info.visible()) {
      problems.add(here + ": @JsonTypeInfo on " + iface.getSimpleName() + " sets defaultImpl or visible, which the document cannot describe");
      ok = false;
    }
    Map<String, Class<?>> byName = new TreeMap<>(Emitter.UTF8_ORDER);
    List<Class<?>> listed = new ArrayList<>();
    for (JsonSubTypes.Type s : subs.value()) {
      listed.add(s.value());
      if (s.name().isEmpty()) {
        problems.add(here + ": @JsonSubTypes.Type " + s.value().getSimpleName() + " of " + iface.getSimpleName() + " needs a name");
        ok = false;
      } else if (byName.put(s.name(), s.value()) != null) {
        problems.add(here + ": two subtypes of " + iface.getSimpleName() + " have the name '" + s.name() + "'");
        ok = false;
      }
      if (s.names().length > 0) {
        problems.add(here + ": @JsonSubTypes.Type names (aliases) on " + iface.getSimpleName() + " cannot be documented");
        ok = false;
      }
    }
    List<Class<?>> permitted = List.of(iface.getPermittedSubclasses());
    if (!(listed.containsAll(permitted) && permitted.containsAll(listed) && listed.size() == permitted.size())) {
      problems.add(
          here + ": @JsonSubTypes of " + iface.getSimpleName() + " must name exactly its permitted subtypes "
              + permitted.stream().map(Class::getSimpleName).sorted().toList() + ", not "
              + listed.stream().map(Class::getSimpleName).sorted().toList());
      ok = false;
    }
    for (Class<?> p : permitted) {
      if (!p.isRecord()) {
        problems.add(here + ": the permitted subtype " + p.getSimpleName() + " of " + iface.getSimpleName() + " must be a record");
        ok = false;
      }
    }
    if (!ok) {
      return new LinkedHashMap<>();
    }
    List<Object> oneOf = new ArrayList<>();
    Map<String, Object> mapping = new LinkedHashMap<>();
    for (Map.Entry<String, Class<?>> e : byName.entrySet()) {
      Map<String, Object> r = schemaOf(JType.of(e.getValue()), here + "." + e.getValue().getSimpleName());
      oneOf.add(r);
      Object target = r.get("$ref");
      if (target != null) {
        mapping.put(e.getKey(), target);
      }
    }
    Map<String, Object> discriminator = new LinkedHashMap<>();
    discriminator.put("propertyName", info.property());
    discriminator.put("mapping", mapping);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("oneOf", List.copyOf(oneOf));
    body.put("discriminator", discriminator);
    return body;
  }

  // ---- registered schemas ----------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private Map<String, Object> render(Schema schema, String path) {
    Object type = schema.keywords.get("$type");
    if (type instanceof Class<?> c) {
      return schemaOf(JType.of(c), path);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : schema.keywords.entrySet()) {
      Object v = e.getValue();
      switch (v) {
        case Schema s -> out.put(e.getKey(), render(s, path));
        case List<?> l -> {
          List<Object> items = new ArrayList<>();
          for (Object item : l) {
            items.add(item instanceof Schema s ? render(s, path) : item);
          }
          out.put(e.getKey(), List.copyOf(items));
        }
        case BigDecimal d -> out.put(e.getKey(), Constraints.number(d));
        default -> out.put(e.getKey(), v);
      }
    }
    return out;
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static Map<String, Object> typed(String type) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    return m;
  }

  private static Map<String, Object> formatted(String type, String format) {
    Map<String, Object> m = typed(type);
    m.put("format", format);
    return m;
  }
}
