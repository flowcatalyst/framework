package io.flowcatalyst.framework.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

/** Reads a YAML file into dotted leaves. No object binding, no reflection. */
final class YamlFiles {
  private YamlFiles() {}

  /** Reads one file if it exists, flattening it into {@code into}; problems are appended. */
  static void read(
      Path file,
      Map<String, Sources.Leaf> into,
      List<ConfigProblem> problems) {
    if (!Files.isRegularFile(file)) {
      return;
    }
    String source = file.toString();
    String text;
    try {
      text = Files.readString(file);
    } catch (IOException e) {
      problems.add(new ConfigProblem(null, null, source, "cannot read file: " + e.getMessage()));
      return;
    }
    Object root;
    try {
      root = new Load(LoadSettings.builder().setLabel(source).build()).loadFromString(text);
    } catch (YamlEngineException | IllegalArgumentException e) {
      problems.add(new ConfigProblem(null, null, source, "invalid YAML: " + firstLine(e)));
      return;
    }
    if (root == null) {
      return;
    }
    if (!(root instanceof Map<?, ?> map)) {
      problems.add(new ConfigProblem(null, null, source, "the top level must be a mapping"));
      return;
    }
    flatten("", map, source, into);
  }

  private static void flatten(
      String prefix, Map<?, ?> map, String source, Map<String, Sources.Leaf> into) {
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String path = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
      put(path, entry.getValue(), source, into);
    }
  }

  private static void put(
      String path, @Nullable Object value, String source, Map<String, Sources.Leaf> into) {
    if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
      flatten(path, nested, source, into);
      return;
    }
    Object leafValue = value instanceof Map<?, ?> ? null : value;
    if (leafValue == null && into.containsKey(path)) {
      return; // a null never hides a value from an earlier file
    }
    into.put(path, new Sources.Leaf(leafValue, source));
  }

  private static String firstLine(Exception e) {
    String m = String.valueOf(e.getMessage());
    int nl = m.indexOf('\n');
    return nl < 0 ? m : m.substring(0, nl);
  }
}
