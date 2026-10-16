package io.flowcatalyst.framework.admin;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PrincipalId;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.openapi.SecurityScheme;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An application with the admin module and one data section ("things"), served in process. */
final class AdminFixture {

  static final ApplicationCode APP = new ApplicationCode("acme");
  static final String CONSOLE = "acme:admin:console:view";
  static final String THINGS_VIEW = "acme:admin:things:view";
  static final String USERS_VIEW = "acme:admin:users:view";

  record ThingList(List<String> things) {}

  static final Operation<NoBody, ThingList> LIST_THINGS =
      Operation.get("/api/admin/things", ThingList.class)
          .id("listAdminThings")
          .tag("admin")
          .permission(THINGS_VIEW)
          .build();

  static final AdminSection THINGS =
      AdminSection.of("things", "Things", "/admin/things", THINGS_VIEW).icon("i-lucide-box").serving(LIST_THINGS);
  static final AdminSection USERS = AdminSection.of("users", "Users", "/admin/users", USERS_VIEW).description("Who may sign in.");

  private AdminFixture() {}

  /** Routes that know the declared operations, and a context from the X-Perms header (401 without it). */
  static Module http() {
    return new Module() {
      @Override
      public String id() {
        return "http";
      }

      @Override
      public void register(AppBuilder app) {
        OpenApi.info(app, "acme", "1");
        OpenApi.securityScheme(app, "session", SecurityScheme.cookie("__Host-fw_session"));
        app.provide(
            Routes.class,
            lookup -> {
              Routes routes = new Routes();
              routes.declare(OpenApi.operations(lookup));
              return routes;
            });
        app.provide(
            ContextResolver.class,
            lookup ->
                request -> {
                  String perms = request.header("X-Perms");
                  if (perms == null) {
                    throw new HttpException(401, "UNAUTHENTICATED", "sign in");
                  }
                  return Context.of(new ClientId("acme"))
                      .withPrincipal(new PrincipalId("usr_1"))
                      .withPermissions(perms.isEmpty() ? Set.of() : Set.of(perms.split(",")));
                });
      }
    };
  }

  /** A module registering sections and binding the things operation. */
  static Module sections(AdminSection... sections) {
    return new Module() {
      @Override
      public String id() {
        return "things";
      }

      @Override
      public void register(AppBuilder app) {
        Admin.section(app, sections);
      }

      @Override
      public void boot(App app) {
        for (AdminSection s : sections) {
          if (s.operations().contains(LIST_THINGS)) {
            app.get(Routes.class).forModule("things").bind(LIST_THINGS, r -> Outcome.ok(new ThingList(List.of("a", "b"))));
          }
        }
      }
    };
  }

  static AppBuilder builder(Module... extra) {
    return builder(Map.of("APP_ENV", "test"), extra);
  }

  static AppBuilder builder(Map<String, String> environment, Module... extra) {
    AppBuilder b =
        App.builder()
            .configDirectory(Path.of("no-such-config"))
            .environment(environment)
            .instantSource(() -> Instant.parse("2026-10-16T08:00:00Z"))
            .module(http())
            .module(AdminModule.of(APP));
    for (Module m : extra) {
      b.module(m);
    }
    return b;
  }

  static App start(Module... extra) {
    return builder(extra).start();
  }

  static TestClient client(App app) {
    return TestClient.of(app.get(Routes.class), app.get(ContextResolver.class));
  }
}
