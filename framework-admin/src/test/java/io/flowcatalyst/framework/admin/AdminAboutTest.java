package io.flowcatalyst.framework.admin;

import static io.flowcatalyst.framework.admin.AdminFixture.CONSOLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Criticality;
import io.flowcatalyst.framework.http.TestResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The about operation (admin contract section 4.2). */
class AdminAboutTest {

  TestResponse about(App app, String perms) {
    return AdminFixture.client(app).get("/api/admin/about").header("X-Perms", perms).send();
  }

  @Test
  void aboutReportsTheApplicationTheEnvironmentTheStartTimeAndEveryCheck() {
    try (App app = AdminFixture.start()) {
      app.health().add("up-check", () -> {});
      app.health().add("slow-info", () -> {
        throw new IllegalStateException("jdbc:postgresql://secret-host/db");
      }, Criticality.INFORMATIONAL);

      TestResponse r = about(app, CONSOLE);

      assertThat(r.status()).isEqualTo(200);
      JsonNode tree = r.tree();
      assertThat(tree.get("application").asString()).isEqualTo("acme");
      assertThat(tree.get("environment").asString()).isEqualTo("test");
      assertThat(tree.get("startedAt").asString()).isEqualTo("2026-10-16T08:00:00Z");
      assertThat(tree.get("ready").asBoolean()).isTrue(); // the failing check is informational
      assertThat(tree.get("checks").get(0).toString()).isEqualTo("{\"name\":\"up-check\",\"status\":\"UP\",\"critical\":true}");
      assertThat(tree.get("checks").get(1).toString()).isEqualTo("{\"name\":\"slow-info\",\"status\":\"DOWN\",\"critical\":false}");
      assertThat(r.body()).doesNotContain("secret-host");
    }
  }

  @Test
  void aCriticalFailureMakesItNotReady() {
    try (App app = AdminFixture.start()) {
      app.health().add("db", () -> {
        throw new IllegalStateException("down");
      });

      assertThat(about(app, CONSOLE).tree().get("ready").asBoolean()).isFalse();
    }
  }

  @Test
  void aboutNeedsTheConsolePermission() {
    try (App app = AdminFixture.start()) {
      assertThat(about(app, "acme:admin:users:view").status()).isEqualTo(403);
    }
  }
}
