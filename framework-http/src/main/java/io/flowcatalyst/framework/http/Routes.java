package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.pipeline.Middleware;
import io.flowcatalyst.framework.pipeline.Pipeline;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where modules declare routes and global middleware, in {@code boot}. Compiled once by the
 * server when it starts listening (or by {@link TestClient}); registration after that throws
 * {@link IllegalStateException}.
 *
 * <p>Registrants are named in duplicate-route errors: take a view with {@link #forModule} and
 * register through it.
 *
 * <pre>{@code
 * Routes routes = app.get(Routes.class).forModule("orders");
 * routes.get("/orders/{id}", orders::show);
 * routes.add(Method.PUT, "/orders/{id}", orders::update, RouteOptions.requiresContext());
 * }</pre>
 */
public final class Routes {

  private record Registration(
      Method method, RoutePattern pattern, String registrant, Handler handler, RouteOptions options) {}

  private record SpaMount(String registrant, Spa spa) {}

  /** State shared by every view. */
  private static final class State {
    final @Nullable String environment;
    final List<Registration> registrations = new ArrayList<>();
    final List<Middleware<Request, Response>> global = new ArrayList<>();
    final List<SpaMount> spas = new ArrayList<>();
    boolean sealed;

    State(@Nullable String environment) {
      this.environment = environment;
    }
  }

  private final State state;
  private final String registrant;

  /**
   * Creates an empty route table that knows no environment: an {@link Spa} mounted on it must have
   * its {@code index.html} (a missing one is a start-up problem, as in a deployed environment).
   */
  public Routes() {
    this(new State(null), "(unattributed)");
  }

  /**
   * Creates an empty route table for an environment. The HTTP module passes {@code APP_ENV}; with
   * {@code local} or {@code test} an {@link Spa} without {@code index.html} starts and answers the
   * "not built" 404 instead of failing start-up (static-assets contract section 3).
   *
   * @param environment {@code APP_ENV}, for example {@code test}
   */
  public Routes(String environment) {
    this(new State(environment), "(unattributed)");
  }

  private Routes(State state, String registrant) {
    this.state = state;
    this.registrant = registrant;
  }

  /**
   * A view of the same table that attributes registrations to a module.
   *
   * @param moduleId the module id
   * @return the view
   */
  public Routes forModule(String moduleId) {
    return new Routes(state, moduleId);
  }

  /**
   * Declares a GET route.
   *
   * @param pattern the pattern, for example {@code /orders/{id}}
   * @param handler the handler
   */
  public void get(String pattern, Handler handler) {
    add(Method.GET, pattern, handler);
  }

  /**
   * Declares a GET route with options.
   *
   * @param pattern the pattern
   * @param handler the handler
   * @param options the route options
   */
  public void get(String pattern, Handler handler, RouteOptions options) {
    add(Method.GET, pattern, handler, options);
  }

  /**
   * Declares a POST route.
   *
   * @param pattern the pattern
   * @param handler the handler
   */
  public void post(String pattern, Handler handler) {
    add(Method.POST, pattern, handler);
  }

  /**
   * Declares a POST route with options.
   *
   * @param pattern the pattern
   * @param handler the handler
   * @param options the route options
   */
  public void post(String pattern, Handler handler, RouteOptions options) {
    add(Method.POST, pattern, handler, options);
  }

  /**
   * Declares a PUT route.
   *
   * @param pattern the pattern
   * @param handler the handler
   */
  public void put(String pattern, Handler handler) {
    add(Method.PUT, pattern, handler);
  }

  /**
   * Declares a PUT route with options.
   *
   * @param pattern the pattern
   * @param handler the handler
   * @param options the route options
   */
  public void put(String pattern, Handler handler, RouteOptions options) {
    add(Method.PUT, pattern, handler, options);
  }

  /**
   * Declares a PATCH route.
   *
   * @param pattern the pattern
   * @param handler the handler
   */
  public void patch(String pattern, Handler handler) {
    add(Method.PATCH, pattern, handler);
  }

  /**
   * Declares a PATCH route with options.
   *
   * @param pattern the pattern
   * @param handler the handler
   * @param options the route options
   */
  public void patch(String pattern, Handler handler, RouteOptions options) {
    add(Method.PATCH, pattern, handler, options);
  }

  /**
   * Declares a DELETE route.
   *
   * @param pattern the pattern
   * @param handler the handler
   */
  public void delete(String pattern, Handler handler) {
    add(Method.DELETE, pattern, handler);
  }

  /**
   * Declares a DELETE route with options.
   *
   * @param pattern the pattern
   * @param handler the handler
   * @param options the route options
   */
  public void delete(String pattern, Handler handler, RouteOptions options) {
    add(Method.DELETE, pattern, handler, options);
  }

  /**
   * Declares a route without options.
   *
   * @param method the method
   * @param pattern the pattern
   * @param handler the handler
   */
  public void add(Method method, String pattern, Handler handler) {
    add(method, pattern, handler, RouteOptions.none());
  }

  /**
   * Declares a route.
   *
   * @param method the method
   * @param pattern the pattern: literal segments and {@code {name}} parameters, no wildcards
   * @param handler the handler
   * @param options the route options
   * @throws IllegalArgumentException when the pattern is malformed
   * @throws IllegalStateException when the server is already listening
   */
  public void add(Method method, String pattern, Handler handler, RouteOptions options) {
    RoutePattern parsed = RoutePattern.parse(pattern);
    synchronized (state) {
      requireOpen();
      state.registrations.add(new Registration(method, parsed, registrant, handler, options));
    }
  }

  /**
   * Adds global middleware, run in registration order inside the framework's own (correlation id,
   * error mapping, access log) and outside context resolution.
   *
   * @param middleware the middleware
   * @throws IllegalStateException when the server is already listening
   */
  public void use(Middleware<Request, Response> middleware) {
    synchronized (state) {
      requireOpen();
      state.global.add(middleware);
    }
  }

  /**
   * Mounts the application's single-page application as the router's fallback: a request whose
   * path matches no route is answered by it (static-assets contract). One per application; the
   * checks against the route table run when the table is compiled.
   *
   * <pre>{@code
   * routes.spa(Spa.classpath("fw-spa").build());
   * }</pre>
   *
   * @param spa the SPA
   * @throws IllegalStateException when the server is already listening
   */
  public void spa(Spa spa) {
    synchronized (state) {
      requireOpen();
      state.spas.add(new SpaMount(registrant, spa));
    }
  }

  private void requireOpen() {
    if (state.sealed) {
      throw new IllegalStateException("routes cannot be registered after the server has started listening");
    }
  }

  /**
   * Checks the table, seals it and builds the dispatcher.
   *
   * @param resolver the context resolver, or null when none is provided
   * @param clock the clock for access log durations
   * @return the dispatcher
   * @throws StartupException listing every duplicate route and every route that requires a
   *     context when there is no resolver
   */
  public Dispatcher compile(@Nullable ContextResolver resolver, MonotonicClock clock) {
    return compile(resolver, clock, Metrics.noop());
  }

  /**
   * Checks the table, seals it and builds the dispatcher, which records
   * {@code fw_http_server_requests_total} and {@code fw_http_server_request_duration_seconds}.
   *
   * @param resolver the context resolver, or null when none is provided
   * @param clock the clock for access log durations
   * @param metrics where request metrics go
   * @return the dispatcher
   * @throws StartupException listing every duplicate route and every route that requires a
   *     context when there is no resolver
   */
  public Dispatcher compile(
      @Nullable ContextResolver resolver, MonotonicClock clock, Metrics metrics) {
    List<Registration> registrations;
    List<Middleware<Request, Response>> global;
    List<SpaMount> spas;
    synchronized (state) {
      state.sealed = true;
      registrations = List.copyOf(state.registrations);
      global = List.copyOf(state.global);
      spas = List.copyOf(state.spas);
    }
    List<Router.Compiled> compiled = new ArrayList<>();
    List<Problem> problems = new ArrayList<>();
    Map<String, Registration> seen = new LinkedHashMap<>();
    Router router = new Router();
    List<String> lines = new ArrayList<>();
    for (Registration r : registrations) {
      String key = r.method() + " " + r.pattern().shape();
      Registration first = seen.putIfAbsent(key, r);
      if (first != null) {
        problems.add(
            Problem.of(
                r.registrant(),
                r.method() + " " + r.pattern().text(),
                "duplicate route: "
                    + r.method()
                    + " "
                    + r.pattern().text()
                    + " (module '"
                    + r.registrant()
                    + "') collides with "
                    + first.method()
                    + " "
                    + first.pattern().text()
                    + " (module '"
                    + first.registrant()
                    + "')"));
        continue;
      }
      if (r.options().needsContext() && resolver == null) {
        problems.add(
            Problem.of(
                r.registrant(),
                r.method() + " " + r.pattern().text(),
                "route "
                    + r.method()
                    + " "
                    + r.pattern().text()
                    + " requires a context but no ContextResolver is provided"));
        continue;
      }
      Pipeline<Request, Response> pipeline = Pipeline.of(r.options().middleware(), r.handler()::handle);
      Router.Compiled route =
          new Router.Compiled(r.method(), r.pattern(), r.registrant(), r.options().needsContext(), pipeline);
      router.add(route);
      compiled.add(route);
      lines.add(r.method() + " " + r.pattern().text() + " (" + r.registrant() + ")");
    }
    SpaHandler spa = null;
    if (!spas.isEmpty()) {
      SpaMount first = spas.getFirst();
      for (SpaMount other : spas.subList(1, spas.size())) {
        problems.add(
            Problem.of(
                other.registrant(),
                "spa",
                "a second SPA: module '"
                    + other.registrant()
                    + "' mounts one at "
                    + other.spa().prefixText()
                    + " but module '"
                    + first.registrant()
                    + "' already mounted one at "
                    + first.spa().prefixText()
                    + "; an application serves one SPA"));
      }
      spa = SpaHandler.mount(first.registrant(), first.spa(), compiled, state.environment, problems, lines);
    }
    if (!problems.isEmpty()) {
      throw new StartupException(problems);
    }
    return new Dispatcher(router, global, resolver, clock, metrics, lines, spa);
  }
}
