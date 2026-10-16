package io.flowcatalyst.framework.ui.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * For every fixture and every string value in its props, one at a time: put an XSS payload there and
 * render. Either the component refuses it (a validated id, signal, path or variant: {@link
 * IllegalArgumentException}) or the output must contain no script or image element and no
 * event-handler attribute, i.e. the payload came out as text or as an attribute value. A template
 * that writes a prop unescaped fails here whichever prop it is.
 */
class XssSweepTest {

  private static final String PAYLOAD = "\"'></textarea></title></style></select><script>alert(1)</script><img src=x onerror=alert(2)><b x='";

  private static void collect(JsonNode node, List<List<Object>> out, List<Object> path) {
    if (node instanceof ObjectNode o) {
      o.properties()
          .forEach(
              e -> {
                List<Object> p = new ArrayList<>(path);
                p.add(e.getKey());
                collect(e.getValue(), out, p);
              });
    } else if (node instanceof ArrayNode a) {
      for (int i = 0; i < a.size(); i++) {
        List<Object> p = new ArrayList<>(path);
        p.add(i);
        collect(a.get(i), out, p);
      }
    } else if (node.isString()) {
      out.add(path);
    }
  }

  private static void set(JsonNode root, List<Object> path, String value) {
    JsonNode cur = root;
    for (int i = 0; i < path.size() - 1; i++) {
      Object k = path.get(i);
      cur = k instanceof Integer n ? cur.get(n) : cur.get((String) k);
    }
    Object last = path.get(path.size() - 1);
    if (last instanceof Integer n) {
      ((ArrayNode) cur).set(n, StringNode.valueOf(value));
    } else {
      ((ObjectNode) cur).set((String) last, StringNode.valueOf(value));
    }
  }

  /** Script and image elements plus event-handler and stray attributes: what a payload would add. */
  static int danger(String html) {
    Document doc = Jsoup.parseBodyFragment(html);
    int n = doc.select("script, img").size();
    for (Element e : doc.getAllElements()) {
      for (var a : e.attributes()) {
        if (a.getKey().startsWith("on") || a.getKey().equals("x")) {
          n++;
        }
      }
    }
    return n;
  }

  @Test
  void aPayloadInAnyStringPropIsRefusedOrNeutralised() throws IOException {
    int rendered = 0;
    int refused = 0;
    try (Stream<Path> dirs = Files.list(FixtureConformanceTest.fixtures())) {
      for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
        String component = dir.getFileName().toString();
        try (Stream<Path> files = Files.list(dir)) {
          for (Path json : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
            JsonNode original = FixtureConformanceTest.MAPPER.readTree(Files.readString(json));
            if (!original.has("props")) {
              continue;
            }
            // trusted slot markup (the golden's own links with handlers) is the baseline
            int baseline = danger(FixtureConformanceTest.render(component, original));
            List<List<Object>> leaves = new ArrayList<>();
            collect(original.get("props"), leaves, new ArrayList<>());
            for (List<Object> leaf : leaves) {
              JsonNode copy = original.deepCopy();
              set(copy.get("props"), leaf, PAYLOAD);
              String where = component + "/" + json.getFileName() + " " + leaf;
              try {
                String out = FixtureConformanceTest.render(component, copy);
                assertThat(danger(out)).as("payload became live markup in %s", where).isEqualTo(baseline);
                rendered++;
              } catch (RuntimeException e) {
                // a validated value (id, signal, path, variant) or a decode error for a typed value
                Throwable root = e;
                while (root.getCause() != null) {
                  root = root.getCause();
                }
                assertThat(root).as("only validation may refuse: %s", where).isInstanceOfAny(IllegalArgumentException.class, tools.jackson.core.JacksonException.class, java.time.DateTimeException.class);
                refused++;
              }
            }
          }
        }
      }
    }
    assertThat(rendered).as("payloads rendered").isGreaterThan(500);
    assertThat(refused).as("payloads refused by validation").isGreaterThan(50);
  }
}
