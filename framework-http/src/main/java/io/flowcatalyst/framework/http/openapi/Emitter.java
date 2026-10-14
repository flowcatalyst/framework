package io.flowcatalyst.framework.http.openapi;

import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.Validator;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Writes the OpenAPI 3.1 document from what modules contributed (contract sections 4 to 8), or
 * lists every problem. Deterministic: the same declarations give the same bytes in any order.
 */
final class Emitter {

  /** UTF-8 byte order, as the contract sorts paths and component names. */
  static final Comparator<String> UTF8_ORDER =
      (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

  /** Schema keyword order (contract section 5.4, plus {@code default} for parameters, MO). */
  static final List<String> KEYWORDS =
      List.of(
          "$ref", "type", "const", "enum", "format", "contentEncoding", "pattern", "minLength", "maxLength",
          "minimum", "maximum", "items", "minItems", "maxItems", "uniqueItems", "properties", "required",
          "additionalProperties", "oneOf", "discriminator", "default", "description");

  private static final List<Method> METHOD_ORDER =
      List.of(Method.GET, Method.PUT, Method.POST, Method.DELETE, Method.PATCH);
  private static final Pattern SCHEME_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
  private static final String ERROR = "Error";

  private Emitter() {}

  static OpenApiDocument emit(List<Contribution> contributions) {
    List<String> problems = new ArrayList<>();
    List<Contribution.Info> infos = new ArrayList<>();
    List<Operation<?, ?>> operations = new ArrayList<>();
    Map<Class<?>, Schema> schemas = new HashMap<>();
    Map<Class<?>, String> names = new HashMap<>();
    Map<Class<?>, Map<String, Schema>> properties = new HashMap<>();
    Map<String, SecurityScheme> security = new HashMap<>();
    for (Contribution c : contributions) {
      switch (c) {
        case Contribution.Info i -> infos.add(i);
        case Contribution.Operations o -> operations.addAll(o.operations());
        case Contribution.TypeSchema t -> {
          if (schemas.put(t.type(), t.schema()) != null) {
            problems.add("OpenApi.schema: " + t.type().getName() + " has two schemas");
          }
        }
        case Contribution.TypeName n -> {
          if (names.put(n.type(), n.name()) != null) {
            problems.add("OpenApi.name: " + n.type().getName() + " has two names");
          }
        }
        case Contribution.PropertySchema p -> {
          if (properties.computeIfAbsent(p.type(), k -> new LinkedHashMap<>()).put(p.property(), p.schema()) != null) {
            problems.add("OpenApi.property: " + p.type().getName() + "." + p.property() + " has two schemas");
          }
        }
        case Contribution.Security s -> {
          if (!SCHEME_NAME.matcher(s.name()).matches()) {
            problems.add("security scheme '" + s.name() + "' must match [A-Za-z][A-Za-z0-9_-]*");
          } else if (security.put(s.name(), s.scheme()) != null) {
            problems.add("security scheme '" + s.name() + "' is contributed twice (module '" + s.module() + "')");
          }
        }
      }
    }
    Map<String, Object> info = new LinkedHashMap<>();
    if (infos.isEmpty()) {
      problems.add("the document has no info: call OpenApi.info(app, title, version) once, in register");
    } else if (infos.size() > 1) {
      problems.add(
          "OpenApi.info is called " + infos.size() + " times (modules "
              + infos.stream().map(Contribution.Info::module).toList() + "); once per application");
    } else {
      info.put("title", infos.getFirst().title());
      info.put("version", infos.getFirst().version());
    }

    // the body validators' constraints, before any schema is walked
    Constraints constraints = new Constraints(problems);
    for (Operation<?, ?> op : operations) {
      Validator<?> v = op.body();
      if (v != null) {
        constraints.collect(v, op.label() + ".body");
      }
    }
    TypeWalker walker = new TypeWalker(schemas, names, properties, constraints, problems);
    walker.reserve(ERROR, errorSchema());

    List<String> schemeOrder = schemeOrder(security.keySet());
    Map<String, Map<Method, Object>> paths = new TreeMap<>(UTF8_ORDER);
    Map<String, Operation<?, ?>> ids = new HashMap<>();
    Map<String, Operation<?, ?>> shapes = new HashMap<>();
    Set<String> tags = new TreeSet<>(UTF8_ORDER);
    for (Operation<?, ?> op : operations) {
      for (String p : op.problems()) {
        problems.add(op.label() + ": " + p);
      }
      String id = op.id();
      if (id != null) {
        Operation<?, ?> first = ids.putIfAbsent(id, op);
        if (first != null && first != op) {
          problems.add(id + ": two operations have this id (" + first.method() + " " + first.pattern() + " and "
              + op.method() + " " + op.pattern() + ")");
          continue;
        } else if (first == op) {
          problems.add(id + ": the operation is declared twice");
          continue;
        }
      }
      String shape = op.method() + " " + op.pattern().replaceAll("\\{[^}]*}", "{}");
      Operation<?, ?> sameShape = shapes.putIfAbsent(shape, op);
      if (sameShape != null) {
        problems.add(op.label() + ": " + op.method() + " " + op.pattern() + " collides with " + sameShape.label());
        continue;
      }
      if (!op.problems().isEmpty()) {
        continue;
      }
      if (op.requiresContext() && security.isEmpty()) {
        problems.add(
            op.label() + ": the operation requires a context but no security scheme was contributed; say how"
                + " callers authenticate (framework-auth contributes them, or OpenApi.securityScheme)");
      }
      tags.addAll(op.tags());
      paths.computeIfAbsent(op.pattern(), k -> new TreeMap<>(Comparator.comparingInt(METHOD_ORDER::indexOf)))
          .put(op.method(), operation(op, walker, schemeOrder, problems));
    }

    if (!problems.isEmpty()) {
      return new OpenApiDocument.Refused(new ArrayList<>(new LinkedHashSet<>(problems)));
    }

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("openapi", "3.1.0");
    doc.put("info", info);
    Map<String, Object> pathsJson = new LinkedHashMap<>();
    paths.forEach(
        (path, methods) -> {
          Map<String, Object> item = new LinkedHashMap<>();
          methods.forEach((m, o) -> item.put(m.name().toLowerCase(java.util.Locale.ROOT), o));
          pathsJson.put(path, item);
        });
    doc.put("paths", pathsJson);
    Map<String, Object> components = new LinkedHashMap<>();
    Map<String, Object> schemasJson = new LinkedHashMap<>();
    walker.components.forEach((name, body) -> schemasJson.put(name, canonical(body)));
    components.put("schemas", schemasJson);
    Map<String, Object> responses = new LinkedHashMap<>();
    responses.put(ERROR, jsonResponse("Error", ref(ERROR)));
    components.put("responses", responses);
    if (!security.isEmpty()) {
      Map<String, Object> schemes = new LinkedHashMap<>();
      for (String name : schemeOrder) {
        schemes.put(name, scheme(java.util.Objects.requireNonNull(security.get(name))));
      }
      components.put("securitySchemes", schemes);
    }
    doc.put("components", components);
    if (!tags.isEmpty()) {
      List<Object> list = new ArrayList<>();
      for (String t : tags) {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("name", t);
        list.add(tag);
      }
      doc.put("tags", list);
    }
    return new OpenApiDocument.Written(JsonText.write(doc));
  }

  // ---- one operation -----------------------------------------------------------------------------

  private static Map<String, Object> operation(
      Operation<?, ?> op, TypeWalker walker, List<String> schemeOrder, List<String> problems) {
    String label = op.label();
    Map<String, Object> o = new LinkedHashMap<>();
    o.put("operationId", op.id());
    if (op.summary() != null) {
      o.put("summary", op.summary());
    }
    if (op.description() != null) {
      o.put("description", op.description());
    }
    if (!op.tags().isEmpty()) {
      o.put("tags", List.copyOf(op.tags()));
    }
    List<Object> parameters = new ArrayList<>();
    for (String name : patternNames(op.pattern())) {
      for (PathParam<?> p : op.pathParams()) {
        if (p.name().equals(name)) {
          Map<String, Object> param = new LinkedHashMap<>();
          param.put("name", name);
          param.put("in", "path");
          param.put("required", true);
          param.put("schema", canonical(walker.schema(p.type(), label + ".path." + name)));
          parameters.add(param);
        }
      }
    }
    for (QueryParam<?> q : op.queryParams()) {
      Map<String, Object> param = new LinkedHashMap<>();
      param.put("name", q.name());
      param.put("in", "query");
      param.put("required", q.isRequired());
      Map<String, Object> element = walker.schema(q.elementType(), label + ".query." + q.name());
      if (q.isRepeated()) {
        param.put("style", "form");
        param.put("explode", true);
        Map<String, Object> array = new LinkedHashMap<>();
        array.put("type", "array");
        array.put("items", element);
        param.put("schema", canonical(array));
      } else {
        Object def = q.defaultValue();
        if (def != null) {
          element.put("default", def instanceof Enum<?> e ? e.name() : def instanceof Integer i ? (long) i : def);
        }
        param.put("schema", canonical(element));
      }
      parameters.add(param);
    }
    if (!parameters.isEmpty()) {
      o.put("parameters", parameters);
    }
    Validator<?> body = op.body();
    if (body != null) {
      Map<String, Object> schema = walker.schema(body.type(), label + ".body");
      if ((!schema.isEmpty() || body.type() == JsonNode.class) && !schema.containsKey("$ref")) {
        problems.add(label + ".body: a request body is a record or a sealed interface, not " + body.type().getSimpleName());
      }
      Map<String, Object> requestBody = new LinkedHashMap<>();
      requestBody.put("required", true);
      requestBody.put("content", content(schema));
      o.put("requestBody", requestBody);
    }
    Map<String, Object> responses = new LinkedHashMap<>();
    if (op.hasResponseBody()) {
      Map<String, Object> schema = walker.schema(op.responseType(), label + ".response");
      if ((!schema.isEmpty() || op.responseType() == JsonNode.class) && !schema.containsKey("$ref")) {
        problems.add(
            label + ".response: a response is a record, a sealed interface or NoBody; never a Map, a list or a"
                + " primitive at the top level");
      }
      responses.put(String.valueOf(op.status()), jsonResponse(successText(op.status()), schema));
    } else {
      Map<String, Object> none = new LinkedHashMap<>();
      none.put("description", "No Content");
      responses.put("204", none);
    }
    Map<Integer, List<Operation.DeclaredError>> declared = new TreeMap<>();
    for (Operation.DeclaredError e : op.errors()) {
      declared.computeIfAbsent(e.status(), k -> new ArrayList<>()).add(e);
    }
    for (int status : op.documentedErrorStatuses()) {
      List<Operation.DeclaredError> errs = declared.get(status);
      if (errs == null) {
        responses.put(String.valueOf(status), responseRef());
      } else {
        List<String> lines = new ArrayList<>();
        for (Operation.DeclaredError e : errs) {
          lines.add(e.code() + ": " + e.message());
        }
        lines.sort(UTF8_ORDER);
        responses.put(String.valueOf(status), jsonResponse(String.join("\n", lines), ref(ERROR)));
      }
    }
    responses.put("default", responseRef());
    o.put("responses", responses);
    if (op.requiresContext() && !schemeOrder.isEmpty()) {
      List<Object> security = new ArrayList<>();
      for (String name : schemeOrder) {
        Map<String, Object> alternative = new LinkedHashMap<>();
        alternative.put(name, List.of());
        security.add(alternative);
      }
      o.put("security", security);
    }
    if (op.permission() != null) {
      o.put("x-fw-permission", op.permission());
    }
    return o;
  }

  private static List<String> patternNames(String pattern) {
    List<String> out = new ArrayList<>();
    var m = Pattern.compile("\\{([a-z][A-Za-z0-9]*)}").matcher(pattern);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  private static String successText(int status) {
    return switch (status) {
      case 201 -> "Created";
      case 202 -> "Accepted";
      default -> "OK";
    };
  }

  // ---- shared parts ------------------------------------------------------------------------------

  private static Map<String, Object> errorSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("error", typed("string"));
    props.put("message", typed("string"));
    Map<String, Object> details = typed("object");
    details.put("additionalProperties", typed("string"));
    props.put("details", details);
    Map<String, Object> m = typed("object");
    m.put("properties", props);
    m.put("required", List.of("error", "message"));
    m.put("additionalProperties", false);
    return m;
  }

  private static Map<String, Object> scheme(SecurityScheme s) {
    Map<String, Object> m = new LinkedHashMap<>();
    switch (s) {
      case SecurityScheme.CookieApiKey c -> {
        m.put("type", "apiKey");
        m.put("in", "cookie");
        m.put("name", c.name());
      }
      case SecurityScheme.HeaderApiKey h -> {
        m.put("type", "apiKey");
        m.put("in", "header");
        m.put("name", h.name());
      }
      case SecurityScheme.Bearer b -> {
        m.put("type", "http");
        m.put("scheme", "bearer");
        m.put("bearerFormat", b.format());
      }
      case SecurityScheme.Basic b -> {
        m.put("type", "http");
        m.put("scheme", "basic");
      }
    }
    return m;
  }

  /** {@code session}, then {@code bearer}, then the others sorted by name. */
  static List<String> schemeOrder(Set<String> names) {
    List<String> out = new ArrayList<>();
    if (names.contains("session")) {
      out.add("session");
    }
    if (names.contains("bearer")) {
      out.add("bearer");
    }
    List<String> rest = new ArrayList<>();
    for (String n : names) {
      if (!n.equals("session") && !n.equals("bearer")) {
        rest.add(n);
      }
    }
    rest.sort(UTF8_ORDER);
    out.addAll(rest);
    return out;
  }

  private static Map<String, Object> responseRef() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("$ref", "#/components/responses/" + ERROR);
    return m;
  }

  private static Map<String, Object> jsonResponse(String description, Map<String, Object> schema) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("description", description);
    m.put("content", content(schema));
    return m;
  }

  private static Map<String, Object> content(Map<String, Object> schema) {
    Map<String, Object> media = new LinkedHashMap<>();
    media.put("schema", canonical(schema));
    Map<String, Object> content = new LinkedHashMap<>();
    content.put("application/json", media);
    return content;
  }

  private static Map<String, Object> ref(String component) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("$ref", TypeWalker.REF + component);
    return m;
  }

  private static Map<String, Object> typed(String type) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    return m;
  }

  /** A schema with its keywords in the contract's order, recursively. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> canonical(Map<String, Object> schema) {
    for (String key : schema.keySet()) {
      if (!KEYWORDS.contains(key)) {
        throw new IllegalStateException("schema keyword '" + key + "' has no place in the keyword order");
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : KEYWORDS) {
      if (!schema.containsKey(key)) {
        continue;
      }
      Object v = schema.get(key);
      switch (key) {
        case "items" -> out.put(key, canonical((Map<String, Object>) v));
        case "additionalProperties" -> out.put(key, v instanceof Map<?, ?> m ? canonical((Map<String, Object>) m) : v);
        case "properties" -> {
          Map<String, Object> props = new LinkedHashMap<>();
          ((Map<String, Object>) v).forEach((k, p) -> props.put(k, canonical((Map<String, Object>) p)));
          out.put(key, props);
        }
        case "oneOf" -> {
          List<Object> list = new ArrayList<>();
          for (Object item : (List<Object>) v) {
            list.add(canonical((Map<String, Object>) item));
          }
          out.put(key, list);
        }
        case "discriminator" -> {
          Map<String, Object> d = (Map<String, Object>) v;
          Map<String, Object> ordered = new LinkedHashMap<>();
          ordered.put("propertyName", d.get("propertyName"));
          Map<String, Object> mapping = new TreeMap<>(UTF8_ORDER);
          mapping.putAll((Map<String, Object>) d.get("mapping"));
          ordered.put("mapping", new LinkedHashMap<>(mapping));
          out.put(key, ordered);
        }
        default -> out.put(key, v);
      }
    }
    return out;
  }
}
