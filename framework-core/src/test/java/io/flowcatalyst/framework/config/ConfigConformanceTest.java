package io.flowcatalyst.framework.config;

import io.flowcatalyst.framework.conformance.JsonVectors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes and checks {@code conformance/config/config.json}, which the Rust implementation reads. */
class ConfigConformanceTest {

  private static final String FILE = "config/config.json";

  /** The enum the vectors' {@code enum} type stands for. */
  enum Mode {
    FAST,
    SLOW_START
  }

  @TempDir Path tmp;

  @Test
  void configVectors() throws IOException {
    ObjectNode doc = JsonVectors.read(FILE);

    for (JsonNode c : doc.get("envNames")) {
      ObjectNode row = (ObjectNode) c;
      row.put("env", Setting.string(row.get("key").asString()).envName());
    }

    for (JsonNode c : doc.get("values")) {
      ObjectNode row = (ObjectNode) c;
      SettingType<?> type = type(row.get("type").asString());
      row.remove("value");
      row.remove("error");
      try {
        row.set("value", normalise(type.parse(row.get("text").asString())));
      } catch (ValueFormatException e) {
        row.put("error", e.getMessage());
      }
    }

    int n = 0;
    for (JsonNode c : doc.get("scenarios")) {
      ObjectNode row = (ObjectNode) c;
      Path dir = tmp.resolve("s" + n++);
      Files.createDirectories(dir);
      var files = row.get("files").properties();
      for (Map.Entry<String, JsonNode> f : files) {
        Files.writeString(dir.resolve(f.getKey()), f.getValue().asString());
      }
      Map<String, String> env = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : row.get("env").properties()) {
        env.put(e.getKey(), e.getValue().asString());
      }
      List<Declaration> declarations = new ArrayList<>();
      for (JsonNode d : row.get("declarations")) {
        declarations.add(new Declaration(d.get("module").asString(), setting(d)));
      }
      row.remove("values");
      row.remove("problems");
      switch (Config.load(dir, env, declarations)) {
        case Config.Ok ok -> {
          ObjectNode values = row.putObject("values");
          for (Declaration d : declarations) {
            values.set(d.setting().key(), find(ok.config(), d.setting()));
          }
        }
        case Config.Failed failed -> {
          ArrayNode problems = row.putArray("problems");
          for (ConfigProblem p : failed.problems()) {
            problems.add(p.toString().replace(dir.toString() + "/", "config/"));
          }
        }
      }
    }

    JsonVectors.check(FILE, doc);
  }

  private static SettingType<?> type(String name) {
    return switch (name) {
      case "string" -> SettingType.STRING;
      case "integer" -> SettingType.INTEGER;
      case "long" -> SettingType.LONG;
      case "boolean" -> SettingType.BOOLEAN;
      case "duration" -> SettingType.DURATION;
      case "stringList" -> SettingType.STRING_LIST;
      case "secret" -> SettingType.SECRET;
      case "enum" -> new SettingType.EnumType<>(List.of(Mode.values()));
      default -> throw new IllegalArgumentException("unknown type in vector: " + name);
    };
  }

  private static Setting<?> setting(JsonNode d) {
    String key = d.get("key").asString();
    Setting<?> s =
        switch (d.get("type").asString()) {
          case "string" -> withDefault(Setting.string(key), d.has("default") ? d.get("default").asString() : null);
          case "integer" -> withDefault(Setting.integer(key), d.has("default") ? d.get("default").asInt() : null);
          case "long" -> withDefault(Setting.longValue(key), d.has("default") ? d.get("default").asLong() : null);
          case "boolean" -> withDefault(Setting.bool(key), d.has("default") ? d.get("default").asBoolean() : null);
          case "duration" ->
              withDefault(
                  Setting.duration(key),
                  d.has("defaultMillis") ? Duration.ofMillis(d.get("defaultMillis").asLong()) : null);
          case "stringList" -> {
            List<String> def = null;
            if (d.has("default")) {
              def = new ArrayList<>();
              for (JsonNode item : d.get("default")) {
                def.add(item.asString());
              }
            }
            yield withDefault(Setting.stringList(key), def);
          }
          case "secret" -> Setting.secret(key);
          case "enum" ->
              withDefault(
                  Setting.enumOf(key, List.of(Mode.values())),
                  d.has("default") ? Mode.valueOf(d.get("default").asString()) : null);
          default -> throw new IllegalArgumentException("unknown type in vector: " + d);
        };
    return d.has("required") && d.get("required").asBoolean() ? s.asRequired() : s;
  }

  private static <T> Setting<T> withDefault(Setting<T> s, @org.jspecify.annotations.Nullable T value) {
    return value == null ? s : s.withDefault(value);
  }

  private static <T> JsonNode find(Config config, Setting<T> s) {
    return config.find(s).map(ConfigConformanceTest::normalise).orElse(JsonVectors.mapper().nullNode());
  }

  private static JsonNode normalise(Object v) {
    var m = JsonVectors.mapper();
    return switch (v) {
      case Integer i -> m.getNodeFactory().numberNode(i);
      case Long l -> m.getNodeFactory().numberNode(l);
      case Boolean b -> m.getNodeFactory().booleanNode(b);
      case Duration d ->
          m.getNodeFactory()
              .stringNode(
                  java.math.BigInteger.valueOf(d.getSeconds())
                      .multiply(java.math.BigInteger.valueOf(1_000_000_000L))
                      .add(java.math.BigInteger.valueOf(d.getNano()))
                      .toString());
      case Secret s -> m.getNodeFactory().stringNode(s.reveal());
      case Enum<?> e -> m.getNodeFactory().stringNode(e.name());
      case List<?> list -> {
        ArrayNode a = m.createArrayNode();
        for (Object item : list) {
          a.add(String.valueOf(item));
        }
        yield a;
      }
      default -> m.getNodeFactory().stringNode(String.valueOf(v));
    };
  }
}
