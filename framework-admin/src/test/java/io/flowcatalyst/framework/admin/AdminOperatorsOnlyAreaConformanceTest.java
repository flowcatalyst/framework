package io.flowcatalyst.framework.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.conformance.Conformance;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.TestResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs {@code conformance/admin/operators-only-area.json}, written by hand for the Go build (admin
 * contract section 3a, rule 6): both skeleton operations, for every tier and permission set, in an
 * area declared for platform operators only. The file is only read, never written.
 */
class AdminOperatorsOnlyAreaConformanceTest {

  @Test
  void theGateVectorHolds() {
    JsonNode vector = JsonMapper.builder().build().readTree(Conformance.read("admin/operators-only-area.json"));
    assertThat(vector.get("application").asString()).isEqualTo(AdminFixture.APP.value());
    JsonNode operations = vector.get("operations");
    JsonNode gate = vector.get("gate");
    assertThat(operations).hasSize(2);
    assertThat(gate.size()).isGreaterThan(0);

    try (App app = AdminFixture.areaBuilder().start()) {
      for (JsonNode c : gate) {
        String name = c.get("name").asString();
        List<String> permissions = new ArrayList<>();
        c.get("permissions").forEach(p -> permissions.add(p.asString()));
        String tier = c.get("tier").asString();
        for (JsonNode op : operations) {
          assertThat(op.get("method").asString()).isEqualTo("GET");
          var call = AdminFixture.client(app).get(op.get("path").asString()).header("X-Perms", String.join(",", permissions));
          if (!tier.isEmpty()) {
            call = call.header("X-Tier", tier);
          }
          TestResponse r = call.send();
          String label = name + " / " + op.get("id").asString();
          if (c.get("allowed").asBoolean()) {
            assertThat(r.status()).as(label).isEqualTo(200);
          } else {
            assertThat(r.status()).as(label).isEqualTo(403);
            assertThat(r.tree().get("error").asString()).as(label).isEqualTo("FORBIDDEN");
            boolean operators = r.tree().get("message").asString().contains("platform operators");
            assertThat(operators).as(label + ": which 403").isEqualTo(c.get("message").asString().equals("operators"));
          }
        }
        if (c.get("allowed").asBoolean()) {
          var call = AdminFixture.client(app).get("/api/admin/navigation").header("X-Perms", String.join(",", permissions));
          if (!tier.isEmpty()) {
            call = call.header("X-Tier", tier);
          }
          List<String> ids = AdminOperatorsOnlyTest.AdminSectionIds.of(call.send());
          List<String> expected = new ArrayList<>();
          c.get("navigation").forEach(s -> expected.add(s.asString()));
          assertThat(ids).as(name + ": navigation").isEqualTo(expected);
        }
      }
    }
  }
}
