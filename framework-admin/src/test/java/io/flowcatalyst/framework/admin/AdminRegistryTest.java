package io.flowcatalyst.framework.admin;

import static io.flowcatalyst.framework.admin.AdminFixture.THINGS;
import static io.flowcatalyst.framework.admin.AdminFixture.THINGS_VIEW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.OpenApiDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The start-up rules (admin contract section 2) and the OpenAPI document. */
class AdminRegistryTest {

  record Out(String x) {}

  static Operation.Builder<NoBody, Out> get(String pattern) {
    return Operation.get(pattern, Out.class).id("op" + Math.abs(pattern.hashCode()));
  }

  @Test
  void anAdminOperationWithoutAPermissionFailsStartUp() {
    Operation<NoBody, Out> open = get("/api/admin/open").build();

    assertThatThrownBy(() -> AdminSection.of("open", "Open", "/admin/open", THINGS_VIEW).serving(open))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has no required permission");
  }

  @Test
  void aSectionOrOperationPermissionNeedsFourSegments() {
    assertThatThrownBy(() -> AdminSection.of("s", "S", "/admin/s", "acme:admin:view"))
        .hasMessageContaining("four ':'-separated segments");
    Operation<NoBody, Out> three = get("/api/admin/three").permission("acme:admin:view").build();
    assertThatThrownBy(() -> AdminSection.of("s", "S", "/admin/s", THINGS_VIEW).serving(three))
        .hasMessageContaining("four ':'-separated segments");
  }

  @Test
  void anOperationOutsideTheAdminApiPrefixIsRefused() {
    Operation<NoBody, Out> elsewhere = get("/api/orders").permission(THINGS_VIEW).build();

    assertThatThrownBy(() -> AdminSection.of("s", "S", "/admin/s", THINGS_VIEW).serving(elsewhere))
        .hasMessageContaining("must be under /api/admin/");
  }

  @Test
  void theIdAndThePathAreChecked() {
    assertThatThrownBy(() -> AdminSection.of("Bad Id", "S", "/admin/s", THINGS_VIEW)).hasMessageContaining("the id must match");
    assertThatThrownBy(() -> AdminSection.of("s", "S", "/orders", THINGS_VIEW)).hasMessageContaining("the path must be /admin");
    assertThatThrownBy(() -> AdminSection.of("s", "S", "/administrators", THINGS_VIEW)).hasMessageContaining("the path must be /admin");
    assertThat(AdminSection.of("s", "S", "/admin", THINGS_VIEW).path()).isEqualTo("/admin");
  }

  @Test
  void twoSectionsWithTheSameIdOrPathFailStartUp() {
    AdminSection a = AdminSection.of("things", "A", "/admin/a", "acme:admin:a:view");
    AdminSection b = AdminSection.of("things", "B", "/admin/b", "acme:admin:b:view");
    AdminSection c = AdminSection.of("c", "C", "/admin/a", "acme:admin:c:view");

    assertThatThrownBy(() -> AdminFixture.start(AdminFixture.sections(a, b)))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("admin section id 'things' is registered twice");
    assertThatThrownBy(() -> AdminFixture.start(AdminFixture.sections(a, c)))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("admin path '/admin/a' is used by two sections");
  }

  @Test
  void aPermissionOfAnotherApplicationFailsStartUp() {
    AdminSection foreign = AdminSection.of("f", "F", "/admin/f", "other:admin:f:view");

    assertThatThrownBy(() -> AdminFixture.start(AdminFixture.sections(foreign)))
        .isInstanceOf(StartupException.class)
        .hasMessageContaining("does not belong to application 'acme'");
  }

  @Test
  void problemsAreReportedTogether() {
    AdminSection a = AdminSection.of("x", "A", "/admin/a", "other:admin:a:view");
    AdminSection b = AdminSection.of("x", "B", "/admin/b", "acme:admin:b:view");

    assertThat(Admin.problems(java.util.List.of(a, b), "acme")).extracting(Problem::message)
        .containsExactly(
            "admin section id 'x' is registered twice",
            "admin permission 'other:admin:a:view' does not belong to application 'acme'");
  }

  @Test
  void permissionsOfTheRegistryAreSortedForDefinitionSync() {
    assertThat(Admin.permissions(java.util.List.of(THINGS, AdminSection.of("u", "U", "/admin/u", "acme:admin:u:manage"))))
        .containsExactly("acme:admin:things:view", "acme:admin:u:manage");
  }

  @Test
  void theOpenApiDocumentHoldsBothOperationsAndEverySectionOperationWithItsPermission() throws Exception {
    var declarations = AdminFixture.builder(AdminFixture.sections(THINGS)).declarations();
    String json =
        switch (OpenApi.document(declarations)) {
          case OpenApiDocument.Written w -> w.json();
          case OpenApiDocument.Refused r -> throw new AssertionError(r.problems());
        };
    JsonNode paths = JsonMapper.builder().build().readTree(json).get("paths");

    assertThat(paths.get("/api/admin/navigation").get("get").get("x-fw-permission").asString()).isEqualTo("acme:admin:console:view");
    assertThat(paths.get("/api/admin/about").get("get").get("operationId").asString()).isEqualTo("getAdminAbout");
    assertThat(paths.get("/api/admin/things").get("get").get("x-fw-permission").asString()).isEqualTo(THINGS_VIEW);
    assertThat(paths.get("/api/admin/things").get("get").get("tags").get(0).asString()).isEqualTo("admin");
  }
}
