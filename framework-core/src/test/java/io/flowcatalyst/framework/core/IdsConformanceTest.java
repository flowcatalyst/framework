package io.flowcatalyst.framework.core;

import io.flowcatalyst.framework.conformance.JsonVectors;
import java.time.Instant;
import java.time.InstantSource;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Writes and checks {@code conformance/tsid/tsid.json}, which the Rust implementation reads. */
class IdsConformanceTest {

  private static final String FILE = "tsid/tsid.json";

  @Test
  void tsidVectors() {
    ObjectNode doc = JsonVectors.read(FILE);

    for (JsonNode c : doc.get("decode")) {
      ObjectNode row = (ObjectNode) c;
      String text = row.get("text").asString();
      OptionalLong value = Ids.toLong(text);
      row.remove("value");
      row.remove("canonical");
      if (value.isPresent()) {
        row.put("value", Long.toUnsignedString(value.getAsLong()));
        row.put("canonical", text.equals(text.toUpperCase(java.util.Locale.ROOT)) && text.charAt(0) <= '7');
      } else {
        row.putNull("value");
      }
    }

    Ids ids = new Ids(InstantSource.system());
    for (JsonNode c : doc.get("prefixes")) {
      ObjectNode row = (ObjectNode) c;
      boolean valid;
      try {
        ids.generate(row.get("prefix").asString());
        valid = true;
      } catch (IllegalArgumentException e) {
        valid = false;
      }
      row.put("valid", valid);
    }

    for (JsonNode c : doc.get("layout")) {
      ObjectNode row = (ObjectNode) c;
      long millis = row.get("millis").asLong();
      Ids fixed = new Ids(InstantSource.fixed(Instant.ofEpochMilli(millis)));
      long value = Ids.toLong(fixed.generate()).orElseThrow();
      row.put("millisBits", Long.toUnsignedString(value >>> 22));
    }

    JsonVectors.check(FILE, doc);
  }
}
