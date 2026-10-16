package io.flowcatalyst.framework.admin;

import static io.flowcatalyst.framework.admin.AdminFixture.CONSOLE;
import static io.flowcatalyst.framework.admin.AdminFixture.THINGS;
import static io.flowcatalyst.framework.admin.AdminFixture.THINGS_VIEW;
import static io.flowcatalyst.framework.admin.AdminFixture.USERS;
import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The permission gate (admin contract section 3): layer 1, the operation's check with the standard
 * 403 envelope, and layer 2, the navigation filter.
 */
class AdminGateTest {

  App app;
  TestClient client;

  @BeforeEach
  void start() {
    app = AdminFixture.start(AdminFixture.sections(THINGS, USERS));
    client = AdminFixture.client(app);
  }

  @AfterEach
  void stop() {
    app.close();
  }

  TestResponse nav(String perms) {
    return client.get("/api/admin/navigation").header("X-Perms", perms).send();
  }

  static List<String> ids(TestResponse r) {
    List<String> ids = new ArrayList<>();
    for (JsonNode s : r.tree().get("sections")) {
      ids.add(s.get("id").asString());
    }
    return ids;
  }

  @Test
  void aCallerWithoutAnyPermissionIsRefusedWithTheForbiddenEnvelope() {
    TestResponse r = nav("");

    assertThat(r.status()).isEqualTo(403);
    assertThat(r.tree().get("error").asString()).isEqualTo("FORBIDDEN");
    assertThat(r.tree().get("message").asString()).isEqualTo("this needs the permission " + CONSOLE);
  }

  @Test
  void aCallerWithAnotherSectionsPermissionIsRefusedAtTheConsole() {
    assertThat(nav(THINGS_VIEW).status()).isEqualTo(403);
    assertThat(client.get("/api/admin/about").header("X-Perms", THINGS_VIEW).send().status()).isEqualTo(403);
  }

  @Test
  void anUnauthenticatedCallerGets401BeforeAnyPermissionCheck() {
    TestResponse r = client.get("/api/admin/navigation").send();

    assertThat(r.status()).isEqualTo(401);
    assertThat(r.tree().get("error").asString()).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void theConsolePermissionOpensTheNavigationButListsOnlyTheSectionsTheCallerMayUse() {
    TestResponse r = nav(CONSOLE);

    assertThat(r.status()).isEqualTo(200);
    assertThat(ids(r)).containsExactly("about");
  }

  @Test
  void eachSectionPermissionAddsItsSectionInRegistrationOrder() {
    assertThat(ids(nav(CONSOLE + "," + AdminFixture.USERS_VIEW))).containsExactly("about", "users");
    assertThat(ids(nav(CONSOLE + "," + THINGS_VIEW + "," + AdminFixture.USERS_VIEW))).containsExactly("about", "things", "users");
  }

  @Test
  void aWildcardGrantOpensEverything() {
    assertThat(ids(nav("acme:admin:*:*"))).containsExactly("about", "things", "users");
    assertThat(ids(nav("acme:admin:*:view"))).containsExactly("about", "things", "users");
  }

  @Test
  void aWildcardOfAnotherApplicationOrAnotherDomainGrantsNothing() {
    assertThat(nav("other:admin:*:*").status()).isEqualTo(403);
    assertThat(nav("acme:orders:*:*").status()).isEqualTo(403);
    assertThat(nav("acme:admin:*").status()).isEqualTo(403); // three segments never match four
  }

  @Test
  void theNavigationCarriesWhatTheShellNeedsAndLeavesOutAbsentFields() {
    JsonNode sections = nav("acme:admin:*:*").tree().get("sections");

    JsonNode things = sections.get(1);
    assertThat(things.get("path").asString()).isEqualTo("/admin/things");
    assertThat(things.get("permission").asString()).isEqualTo(THINGS_VIEW);
    assertThat(things.get("icon").asString()).isEqualTo("i-lucide-box");
    assertThat(things.has("description")).isFalse();
    assertThat(sections.get(2).has("icon")).isFalse();
    assertThat(sections.get(2).get("description").asString()).isEqualTo("Who may sign in.");
  }

  @Test
  void aSectionsOwnOperationIsGatedByItsOwnPermissionNotTheConsoles() {
    assertThat(client.get("/api/admin/things").header("X-Perms", CONSOLE).send().status()).isEqualTo(403);
    assertThat(client.get("/api/admin/things").header("X-Perms", USERS.permission()).send().status()).isEqualTo(403);

    TestResponse ok = client.get("/api/admin/things").header("X-Perms", THINGS_VIEW).send();
    assertThat(ok.status()).isEqualTo(200);
    assertThat(ok.tree().get("things").size()).isEqualTo(2);
  }

  @Test
  void aliasedCaseOrPrefixPermissionsDoNotMatch() {
    assertThat(nav("ACME:admin:console:view").status()).isEqualTo(403);
    assertThat(nav("acme:admin:console:vie").status()).isEqualTo(403);
    assertThat(nav("acme:admin:console:view:extra").status()).isEqualTo(403);
  }
}
