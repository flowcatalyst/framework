package io.flowcatalyst.framework.ui.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders every fixture of {@code ui-kit/fixtures} through the kit and compares it with the golden
 * HTML after normalisation ({@link HtmlNorm}). A fixture directory with no renderer is listed in
 * {@link #NOT_YET}; the list is asserted both ways so it cannot rot.
 */
class FixtureConformanceTest {

  /** Fixture directories this module does not render yet. Shrinks to empty as the kit grows. */
  static final Set<String> NOT_YET = Set.of(
          "app-shell",
          "app-sidebar",
          "autocomplete",
          "autocomplete-options",
          "code-block",
          "code-chips",
          "confirm-dialog",
          "cursor-pager",
          "data-table",
          "date-range",
          "detail-field",
          "dialog",
          "drawer",
          "drawer-content",
          "empty-row",
          "form-dialog",
          "form-section",
          "local-time",
          "multi-select",
          "pagination",
          "paginator",
          "pick-list",
          "row-actions",
          "sheet",
          "sortable-header",
          "table",
          "table-cell",
          "table-head",
          "table-row",
          "table-toolbar",
          "table-toolbar-state",
          "tabs",
          "user-menu");

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  static Path fixtures() {
    String env = System.getenv("UI_KIT_FIXTURES");
    return Path.of(env != null ? env : "../ui-kit/fixtures").toAbsolutePath().normalize();
  }

  /** Java keywords in the specs' prop names: {@code class} is {@code cls}, {@code for} is {@code forId}. */
  static JsonNode rename(JsonNode n, String key) {
    if (n instanceof ObjectNode o) {
      ObjectNode out = MAPPER.createObjectNode();
      boolean attrs = key.equals("attrs") || key.equals("inputAttrs");
      o.properties()
          .forEach(
              e -> {
                String k = e.getKey();
                if (!attrs) {
                  k = k.equals("class") ? "cls" : k.equals("for") ? "forId" : k;
                }
                out.set(k, rename(e.getValue(), e.getKey()));
              });
      return out;
    }
    if (n.isArray()) {
      var out = MAPPER.createArrayNode();
      n.forEach(x -> out.add(rename(x, key)));
      return out;
    }
    return n;
  }

  static String renderFixture(String component, Path json) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readString(json));
    Set<String> allowed = Set.of("props", "slots", "source", "notes");
    root.propertyNames().forEach(k -> assertThat(allowed).as("fixture key " + k).contains(k));
    Renderers.Renderer renderer = Renderers.ALL.get(component);
    JsonNode props = root.has("props") ? rename(root.get("props"), "props") : MAPPER.createObjectNode();
    Object decoded = MAPPER.treeToValue(props, renderer.props());
    Map<String, String> slots = new HashMap<>();
    if (root.has("slots")) {
      root.get("slots").properties().forEach(e -> slots.put(e.getKey(), e.getValue().asString()));
    }
    Renderers.Slots s = new Renderers.Slots(slots);
    String html = Markup.render(renderer.fn().apply(decoded, s));
    assertThat(s.used).as("slots the fixture fills must exist on the component").containsAll(slots.keySet());
    return html;
  }

  @TestFactory
  Stream<DynamicTest> everyFixtureMatchesItsGolden() throws IOException {
    Path root = fixtures();
    List<DynamicTest> tests = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
        String component = dir.getFileName().toString();
        if (!Renderers.ALL.containsKey(component)) {
          continue;
        }
        try (Stream<Path> files = Files.list(dir)) {
          for (Path json : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
            String name = component + "/" + json.getFileName().toString().replace(".json", "");
            tests.add(
                DynamicTest.dynamicTest(
                    name,
                    () -> {
                      Path golden = Path.of(json.toString().replace(".json", ".html"));
                      String want = HtmlNorm.normalize(Files.readString(golden));
                      String got = HtmlNorm.normalize(renderFixture(component, json));
                      assertThat(got).as("%s differs from its golden", name).isEqualTo(want);
                    }));
          }
        }
      }
    }
    assertThat(tests).isNotEmpty();
    return tests.stream();
  }

  @Test
  void theNotYetListIsExactlyTheFixturesWithoutARenderer() throws IOException {
    Set<String> missing = new TreeSet<>();
    try (Stream<Path> dirs = Files.list(fixtures())) {
      dirs.filter(Files::isDirectory)
          .map(d -> d.getFileName().toString())
          .filter(n -> !Renderers.ALL.containsKey(n))
          .forEach(missing::add);
    }
    assertThat(missing).isEqualTo(new TreeSet<>(NOT_YET));
    Set<String> orphan = new TreeSet<>(Renderers.ALL.keySet());
    try (Stream<Path> dirs = Files.list(fixtures())) {
      dirs.filter(Files::isDirectory).forEach(d -> orphan.remove(d.getFileName().toString()));
    }
    assertThat(orphan).as("renderers without fixtures").isEmpty();
  }
}
