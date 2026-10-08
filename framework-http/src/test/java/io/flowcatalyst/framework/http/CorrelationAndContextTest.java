package io.flowcatalyst.framework.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.StartupException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CorrelationAndContextTest {

  private static TestClient echoClient() {
    Routes routes = new Routes();
    routes.get("/x", r -> Response.ok(Map.of("cid", r.correlationId().value())));
    return TestClient.of(routes);
  }

  @Test
  void aValidSuppliedIdIsUsedAndEchoed() {
    TestResponse r = echoClient().get("/x").header("X-Correlation-Id", "req-42.a_b").send();
    assertThat(r.header("X-Correlation-Id")).isEqualTo("req-42.a_b");
    assertThat(r.tree().get("cid").asString()).isEqualTo("req-42.a_b");
  }

  @Test
  void anIdIsGeneratedWhenAbsent() {
    TestResponse a = echoClient().get("/x").send();
    TestResponse b = echoClient().get("/x").send();
    assertThat(a.header("X-Correlation-Id")).isNotBlank().isNotEqualTo(b.header("X-Correlation-Id"));
    assertThat(a.tree().get("cid").asString()).isEqualTo(a.header("X-Correlation-Id"));
  }

  @Test
  void anInvalidSuppliedIdIsReplacedNotRejected() {
    for (String bad : List.of("has space", "semi;colon", "x".repeat(101), "")) {
      TestResponse r = echoClient().get("/x").header("X-Correlation-Id", bad).send();
      assertThat(r.status()).isEqualTo(200);
      assertThat(r.header("X-Correlation-Id")).isNotEqualTo(bad).matches("[A-Za-z0-9._-]{1,100}");
    }
  }

  @Test
  void aHundredCharacterIdIsValid() {
    String id = "x".repeat(100);
    assertThat(echoClient().get("/x").header("X-Correlation-Id", id).send().header("X-Correlation-Id"))
        .isEqualTo(id);
  }

  @Test
  void headerNamesAreCaseInsensitive() {
    assertThat(echoClient().get("/x").header("x-correlation-id", "low").send().header("X-Correlation-Id"))
        .isEqualTo("low");
  }

  @Test
  void the404CarriesTheHeaderToo() {
    assertThat(echoClient().get("/nope").header("X-Correlation-Id", "n1").send().header("X-Correlation-Id"))
        .isEqualTo("n1");
  }

  @Test
  void theResolvedContextCarriesTheRequestsCorrelationId() {
    Routes routes = new Routes();
    AtomicReference<Context> seen = new AtomicReference<>();
    routes.get(
        "/c",
        r -> {
          seen.set(r.context());
          return Response.noContent();
        },
        RouteOptions.requiresContext());
    // the resolver builds a context with its own, different correlation id
    TestClient client = TestClient.of(routes, r -> Context.of(new ClientId("acme")));
    client.get("/c").header("X-Correlation-Id", "from-request").send();
    assertThat(seen.get().client().value()).isEqualTo("acme");
    assertThat(seen.get().correlationId().value()).isEqualTo("from-request");
  }

  @Test
  void requestContextOnARouteWithoutRequiresContextThrows() {
    Routes routes = new Routes();
    routes.get("/c", r -> Response.ok(Map.of("c", r.context().toString())));
    // IllegalStateException is a programming error: 500
    assertThat(TestClient.of(routes, r -> Context.of(new ClientId("acme"))).get("/c").send().status())
        .isEqualTo(500);
    assertThatIllegalStateException().isThrownBy(() -> Request.of(rawGet()).context());
  }

  private static RawRequest rawGet() {
    return new RawRequest(
        Method.GET, "/", null, Map.of(), new java.io.ByteArrayInputStream(new byte[0]), 0, 100, "HTTP/1.1");
  }

  @Test
  void aRouteRequiringContextWithoutAResolverFailsStartUpNamingTheRoute() {
    Routes routes = new Routes();
    routes.forModule("orders").get("/orders/{id}", r -> Response.noContent(), RouteOptions.requiresContext());
    routes.get("/public", r -> Response.noContent());
    StartupException e = (StartupException) catchThrowable(() -> TestClient.of(routes));
    assertThat(e.problems()).hasSize(1);
    assertThat(e.problems().getFirst().message()).contains("GET /orders/{id}", "ContextResolver");
    assertThat(e.problems().getFirst().module()).isEqualTo("orders");
  }

  @Test
  void everyRouteNeedingAContextIsReported() {
    Routes routes = new Routes();
    routes.get("/a", r -> Response.noContent(), RouteOptions.requiresContext());
    routes.get("/b", r -> Response.noContent(), RouteOptions.requiresContext());
    StartupException e = (StartupException) catchThrowable(() -> TestClient.of(routes));
    assertThat(e.problems()).hasSize(2);
  }

  @Test
  void contextResolutionRunsAfterGlobalAndBeforeRouteMiddleware() {
    List<String> trace = new ArrayList<>();
    Routes routes = new Routes();
    routes.use(
        (r, next) -> {
          trace.add("global");
          return next.handle(r);
        });
    routes.get(
        "/c",
        r -> {
          trace.add("handler");
          return Response.noContent();
        },
        RouteOptions.requiresContext()
            .with(
                (r, next) -> {
                  trace.add("route-mw:context=" + (r.context() != null));
                  return next.handle(r);
                }));
    TestClient client =
        TestClient.of(
            routes,
            r -> {
              trace.add("resolver");
              return Context.of(new ClientId("acme"));
            });
    client.get("/c").send();
    assertThat(trace).containsExactly("global", "resolver", "route-mw:context=true", "handler");
  }

  @Test
  void globalMiddlewareRunsInRegistrationOrderAndRouteMiddlewareInsideIt() {
    List<String> trace = new ArrayList<>();
    Routes routes = new Routes();
    for (String name : List.of("g1", "g2")) {
      routes.use(
          (r, next) -> {
            trace.add(name);
            return next.handle(r);
          });
    }
    routes.get(
        "/c",
        r -> {
          trace.add("handler");
          return Response.noContent();
        },
        RouteOptions.none()
            .with((r, next) -> { trace.add("r1"); return next.handle(r); })
            .with((r, next) -> { trace.add("r2"); return next.handle(r); }));
    TestClient.of(routes).get("/c").send();
    assertThat(trace).containsExactly("g1", "g2", "r1", "r2", "handler");
  }

  @Test
  void middlewareCanShortCircuitAndHaveItsExceptionsMapped() {
    Routes routes = new Routes();
    routes.use(
        (r, next) -> {
          if (r.header("X-Block") != null) {
            throw new HttpException(403, "BLOCKED", "blocked");
          }
          return next.handle(r);
        });
    routes.get("/c", r -> Response.noContent());
    TestClient c = TestClient.of(routes);
    TestResponse blocked = c.get("/c").header("X-Block", "1").header("X-Correlation-Id", "k").send();
    assertThat(blocked.status()).isEqualTo(403);
    assertThat(blocked.tree().get("error").asString()).isEqualTo("BLOCKED");
    assertThat(blocked.header("X-Correlation-Id")).isEqualTo("k");
    assertThat(c.get("/c").send().status()).isEqualTo(204);
  }

  @Test
  void middlewarePassesValuesToHandlersThroughTypedAttributes() {
    Request.Key<String> user = Request.Key.of("user", String.class);
    Routes routes = new Routes();
    routes.use(
        (r, next) -> {
          r.attribute(user, "ada");
          return next.handle(r);
        });
    routes.get("/c", r -> Response.text(200, r.attribute(user)));
    assertThat(TestClient.of(routes).get("/c").send().body()).isEqualTo("ada");
  }
}
