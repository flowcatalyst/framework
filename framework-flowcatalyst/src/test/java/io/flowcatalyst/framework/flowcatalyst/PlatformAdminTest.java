package io.flowcatalyst.framework.flowcatalyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.admin.AdminModule;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.SecurityScheme;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The event types and roles sections (M4e slice 2): typed read-only operations over the platform
 * reads an application service account may call, behind the admin permission, confined to the
 * application's own records.
 */
class PlatformAdminTest {

  static final ApplicationCode APP = new ApplicationCode("orders");
  static final String CONSOLE = "orders:admin:console:view";
  static final String ET = "orders:admin:event-types:view";
  static final String ROLES = "orders:admin:roles:view";

  @TempDir Path dir;

  FakePlatform platform;
  App app;
  TestClient client;

  @BeforeEach
  void start() {
    platform = FakePlatform.start();
    platform.respondGet("/api/event-types", 200, eventTypes());
    platform.respondGet("/api/event-types/et_1", 200, eventType("et_1", "orders:sales:order:placed", "Order placed", "orders", "CURRENT", true));
    platform.respondGet("/api/event-types/et_other", 200, eventType("et_other", "billing:invoice:invoice:issued", "Issued", "billing", "CURRENT", false));
    platform.respondGet("/api/roles", 200, roles());
    platform.respondGet(
        "/api/roles/orders:clerk",
        200,
        role("rol_1", "orders:clerk", "orders", "Clerk", "SDK", "orders:sales:order:write", "orders:sales:order:read"));
    platform.respondGet("/api/roles/billing:admin", 200, role("rol_9", "billing:admin", "billing", "Billing admin", "SDK", "billing:x:y:z"));
    app = starting(Map.of());
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
  }

  App starting(Map<String, String> more) {
    Map<String, String> env = new java.util.HashMap<>(Receiving.env(platform));
    env.putAll(more);
    AppBuilder b = App.builder().configDirectory(dir).environment(env);
    b.module(http()).module(AdminModule.of(APP)).module(PlatformAdminModule.of(APP)).module(new FlowCatalystModule());
    return b.start();
  }

  @AfterEach
  void stop() {
    app.close();
    platform.close();
  }

  static Module http() {
    return new Module() {
      @Override
      public String id() {
        return "http";
      }

      @Override
      public void register(AppBuilder b) {
        OpenApi.info(b, "orders", "1");
        OpenApi.securityScheme(b, "session", SecurityScheme.cookie("__Host-fw_session"));
        b.provide(
            Routes.class,
            lookup -> {
              Routes routes = new Routes();
              routes.declare(OpenApi.operations(lookup));
              return routes;
            });
        b.provide(
            ContextResolver.class,
            lookup ->
                request -> {
                  String perms = request.header("X-Perms");
                  if (perms == null) {
                    throw new HttpException(401, "UNAUTHENTICATED", "sign in");
                  }
                  return Context.of(new ClientId("orders"))
                      .withPrincipal(new PrincipalId("usr_1"))
                      .withPermissions(perms.isEmpty() ? Set.of() : Set.of(perms.split(",")));
                });
      }
    };
  }

  // ---- platform bodies ----------------------------------------------------------------------

  static String eventType(String id, String code, String name, String application, String status, boolean versions) {
    String[] p = code.split(":");
    return "{\"id\":\"" + id + "\",\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"description\":\"About " + name
        + "\",\"application\":\"" + application + "\",\"subdomain\":\"" + p[1] + "\",\"aggregate\":\"" + p[2]
        + "\",\"eventName\":\"" + p[3] + "\",\"status\":\"" + status + "\",\"specVersions\":"
        + (versions ? "[{\"version\":\"1.0\",\"status\":\"CURRENT\",\"schema\":{\"type\":\"object\"}}]" : "[]") + "}";
  }

  static String eventTypes() {
    return "{\"items\":["
        + eventType("et_2", "orders:sales:order:shipped", "Order shipped", "orders", "CURRENT", false) + ","
        + eventType("et_1", "orders:sales:order:placed", "Order placed", "orders", "CURRENT", true) + ","
        + eventType("et_3", "orders:stock:item:counted", "Item counted", "orders", "ARCHIVED", false) + ","
        + eventType("et_other", "billing:invoice:invoice:issued", "Issued", "billing", "CURRENT", false)
        + "]}";
  }

  static String role(String id, String name, String app, String display, String source, String... permissions) {
    List<String> quoted = new ArrayList<>();
    for (String p : permissions) {
      quoted.add("\"" + p + "\"");
    }
    return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"applicationCode\":\"" + app + "\",\"displayName\":\"" + display
        + "\",\"source\":\"" + source + "\",\"clientManaged\":false,\"permissions\":[" + String.join(",", quoted) + "]}";
  }

  static String roles() {
    return "{\"roles\":["
        + role("rol_1", "orders:clerk", "orders", "Clerk", "SDK", "orders:sales:order:write", "orders:sales:order:read") + ","
        + role("rol_2", "orders:admin", "orders", "Administrator", "SDK", "orders:admin:console:view") + ","
        + role("rol_8", "platform:super-admin", "platform", "Super", "CODE", "platform:*:*:*") + ","
        + role("rol_9", "billing:admin", "billing", "Billing admin", "SDK", "billing:x:y:z")
        + "],\"total\":4}";
  }

  TestResponse get(String path, String perms) {
    return client.get(path).header("X-Perms", perms).send();
  }

  static List<String> field(JsonNode page, String name) {
    List<String> out = new ArrayList<>();
    for (JsonNode n : page.get("items")) {
      out.add(n.get(name).asString());
    }
    return out;
  }

  // ---- event types ---------------------------------------------------------------------------

  @Test
  void eventTypesAreListedSortedAndOnlyThisApplicationsAndTheReadIsFilteredByApplication() {
    TestResponse r = get("/api/admin/event-types", ET);

    assertThat(r.status()).isEqualTo(200);
    assertThat(field(r.tree(), "code"))
        .containsExactly("orders:sales:order:placed", "orders:sales:order:shipped", "orders:stock:item:counted");
    assertThat(r.tree().has("nextCursor")).isFalse();
    JsonNode placed = r.tree().get("items").get(0);
    assertThat(placed.get("event").asString()).isEqualTo("placed");
    assertThat(placed.get("versionCount").asInt()).isEqualTo(1);
    assertThat(placed.get("status").asString()).isEqualTo("CURRENT");
    // the platform was asked for this application only, as the service account
    FakePlatform.Recorded read = platform.requests().stream().filter(x -> x.path().equals("/api/event-types")).findFirst().orElseThrow();
    assertThat(read.query()).contains("application=orders");
    assertThat(read.headers().get("authorization")).startsWith("Bearer ");
  }

  @Test
  void eventTypeFiltersAreSentToThePlatformAndTextSearchIsLocal() {
    get("/api/admin/event-types?status=ARCHIVED&subdomain=stock&aggregate=item", ET);
    FakePlatform.Recorded read = platform.requests().stream().filter(x -> x.path().equals("/api/event-types")).findFirst().orElseThrow();
    assertThat(read.query()).contains("status=ARCHIVED").contains("subdomain=stock").contains("aggregate=item");

    TestResponse q = get("/api/admin/event-types?q=SHIPPED", ET);
    assertThat(field(q.tree(), "code")).containsExactly("orders:sales:order:shipped");
  }

  @Test
  void eventTypesPageByCursorWithoutRepeatsOrGaps() {
    TestResponse first = get("/api/admin/event-types?limit=2", ET);
    assertThat(field(first.tree(), "code")).containsExactly("orders:sales:order:placed", "orders:sales:order:shipped");
    String cursor = first.tree().get("nextCursor").asString();

    TestResponse second = get("/api/admin/event-types?limit=2&after=" + cursor, ET);
    assertThat(field(second.tree(), "code")).containsExactly("orders:stock:item:counted");
    assertThat(second.tree().has("nextCursor")).isFalse();
  }

  @Test
  void anEventTypeDetailHasItsVersionsAndAnotherApplicationsIsA404() {
    TestResponse one = get("/api/admin/event-types/et_1", ET);
    assertThat(one.status()).isEqualTo(200);
    assertThat(one.tree().get("eventType").get("code").asString()).isEqualTo("orders:sales:order:placed");
    assertThat(one.tree().get("versions").get(0).get("version").asString()).isEqualTo("1.0");
    assertThat(one.tree().get("versions").get(0).get("schema").asString()).contains("\"type\"");

    TestResponse foreign = get("/api/admin/event-types/et_other", ET);
    assertThat(foreign.status()).isEqualTo(404);
    assertThat(foreign.tree().get("error").asString()).isEqualTo("EVENT_TYPE_NOT_FOUND");
    assertThat(foreign.body()).doesNotContain("billing");

    platform.respondGet("/api/event-types/et_gone", 404, "{\"error\":\"nope\"}");
    assertThat(get("/api/admin/event-types/et_gone", ET).status()).isEqualTo(404);
  }

  // ---- roles ---------------------------------------------------------------------------------

  @Test
  void rolesAreOnlyThisApplicationsAndTheListShowsPermissionCounts() {
    TestResponse r = get("/api/admin/roles", ROLES);

    assertThat(r.status()).isEqualTo(200);
    assertThat(field(r.tree(), "name")).containsExactly("orders:admin", "orders:clerk");
    assertThat(r.body()).doesNotContain("billing").doesNotContain("platform:super-admin");
    assertThat(r.tree().get("items").get(1).get("permissionCount").asInt()).isEqualTo(2);
    assertThat(field(get("/api/admin/roles?q=clerk", ROLES).tree(), "name")).containsExactly("orders:clerk");
    assertThat(field(get("/api/admin/roles?source=CODE", ROLES).tree(), "name")).isEmpty();
    assertThat(get("/api/admin/roles?limit=1", ROLES).tree().get("nextCursor").asString()).isEqualTo("orders:admin");
  }

  @Test
  void aRoleDetailListsSortedPermissionsAndNeverAnotherApplicationsRole() {
    TestResponse one = get("/api/admin/roles/orders:clerk", ROLES);
    assertThat(one.status()).isEqualTo(200);
    assertThat(one.tree().get("permissions")).extracting(JsonNode::asString).containsExactly("orders:sales:order:read", "orders:sales:order:write");

    long before = platform.requests().size();
    TestResponse foreign = get("/api/admin/roles/billing:admin", ROLES);
    assertThat(foreign.status()).isEqualTo(404);
    assertThat(foreign.tree().get("error").asString()).isEqualTo("ROLE_NOT_FOUND");
    // refused before the platform was asked
    assertThat(platform.requests().size()).isEqualTo(before);
  }

  // ---- the gate and the platform's answers -----------------------------------------------------

  @Test
  void everyOperationNeedsItsOwnSectionsPermission() {
    for (String path : List.of("/api/admin/event-types", "/api/admin/event-types/et_1")) {
      assertThat(get(path, "").status()).as(path).isEqualTo(403);
      assertThat(get(path, CONSOLE + "," + ROLES).status()).as(path).isEqualTo(403);
      assertThat(get(path, "orders:admin:*:*").status()).as(path).isEqualTo(200);
    }
    for (String path : List.of("/api/admin/roles", "/api/admin/roles/orders:clerk")) {
      assertThat(get(path, "").status()).as(path).isEqualTo(403);
      assertThat(get(path, CONSOLE + "," + ET).status()).as(path).isEqualTo(403);
      assertThat(get(path, "orders:admin:roles:view").status()).as(path).isEqualTo(200);
    }
    TestResponse none = get("/api/admin/roles", "");
    assertThat(none.tree().get("error").asString()).isEqualTo("FORBIDDEN");
    assertThat(none.tree().get("message").asString()).isEqualTo("this needs the permission " + ROLES);
    assertThat(client.get("/api/admin/roles").send().status()).isEqualTo(401);
  }

  @Test
  void aRefusedCallerNeverReachesThePlatform() {
    long before = platform.requests().size();
    get("/api/admin/roles", CONSOLE);
    get("/api/admin/event-types", CONSOLE);
    assertThat(platform.requests().size()).isEqualTo(before);
  }

  @Test
  void aPlatformRefusalIsA502WithoutHostsOrTokensAndNamesTheMissingPermission() {
    platform.respondGet("/api/roles", 403, "{\"error\":\"forbidden\",\"message\":\"internal-host-9\"}");

    TestResponse r = get("/api/admin/roles", ROLES);

    assertThat(r.status()).isEqualTo(502);
    assertThat(r.tree().get("error").asString()).isEqualTo("PLATFORM_ERROR");
    assertThat(r.tree().get("message").asString()).contains("service account").contains("view permission");
    assertThat(r.body()).doesNotContain("internal-host-9").doesNotContain("127.0.0.1").doesNotContain("Bearer");
  }

  @Test
  void aPlatformOutageIsA502() {
    platform.respondGet("/api/event-types", 500, "{\"error\":\"boom 127.0.0.1\"}");

    TestResponse r = get("/api/admin/event-types", ET);

    assertThat(r.status()).isEqualTo(502);
    assertThat(r.tree().get("error").asString()).isEqualTo("PLATFORM_ERROR");
    assertThat(r.body()).doesNotContain("127.0.0.1");
  }

  @Test
  void withoutAPlatformConnectionTheAnswerIs503() {
    app.close();
    app = starting(Map.of("FLOWCATALYST_ENABLED", "false"));
    client = TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));

    TestResponse r = get("/api/admin/roles", ROLES);

    assertThat(r.status()).isEqualTo(503);
    assertThat(r.tree().get("error").asString()).isEqualTo("PLATFORM_NOT_CONFIGURED");
  }

  @Test
  void theNavigationListsTheSectionsTheCallerHoldsInRegistrationOrder() {
    JsonNode nav = get("/api/admin/navigation", CONSOLE + "," + ROLES + "," + ET).tree();
    assertThat(nav.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about", "event-types", "roles");
    JsonNode some = get("/api/admin/navigation", CONSOLE + "," + ROLES).tree();
    assertThat(some.get("sections")).extracting(n -> n.get("id").asString()).containsExactly("about", "roles");
  }
}
