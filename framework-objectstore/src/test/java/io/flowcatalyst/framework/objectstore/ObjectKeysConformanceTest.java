package io.flowcatalyst.framework.objectstore;

import io.flowcatalyst.framework.conformance.Conformance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key rules as a shared vector ({@code conformance/objectstore/keys.json}): every candidate key and
 * whether {@link ObjectKeys#check} accepts it. The Go and Rust implementations compare their own checks
 * with the file. Run with {@code -Dconformance.update=true} to rewrite it after adding candidates.
 */
class ObjectKeysConformanceTest {

  private static List<String> candidates() {
    List<String> c = new ArrayList<>();
    // accepted
    c.addAll(List.of("a", "A1", "0", "a.b", "a-b_c", "a/b/c", "a/b.json", "UPPER/lower", "a..b", "a.", "a-", "a_", "9/9", "sets/2026-10-21/apply-1.json"));
    c.add("x".repeat(512));
    c.add("a/".repeat(255) + "a"); // 511 characters
    // refused
    c.addAll(List.of("", "/", "/a", "a/", "a//b", ".", "..", "a/..", "../a", "a/./b", "a/../b", ".hidden", "a/.hidden", "-a", "_a", "a/-b", "a b", "a/ b", "a\\b", "a\u0000b", "a\nb", "a\tb", "é", "a/é", "a:b", "a?b", "a#b", "a%2e", "a*b", "a@b"));
    c.add("x".repeat(513));
    return c;
  }

  @Test
  void theKeyRulesMatchTheSharedVector() throws Exception {
    List<Map<String, Object>> cases = new ArrayList<>();
    for (String key : candidates()) {
      boolean valid;
      try {
        ObjectKeys.check(key);
        valid = true;
      } catch (IllegalArgumentException e) {
        valid = false;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", key.length() > 60 ? "<repeat>" + key.length() : key);
      if (key.length() > 60) {
        row.put("repeat", key.length() == 512 ? "x" : key.length() == 513 ? "x" : "a/");
        row.put("length", key.length());
      }
      row.put("valid", valid);
      cases.add(row);
    }
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put(
        "description",
        "Object store key rules (object-stores contract, section 2): each candidate key and whether it is accepted. A long key is described as 'repeat' (the text repeated until the key is 'length' characters, the last repeat cut) instead of written out.");
    doc.put("cases", cases);
    String json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build().writeValueAsString(doc);
    Conformance.check("objectstore/keys.json", json);
  }
}
