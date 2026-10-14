package io.flowcatalyst.framework.http;

import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.pipeline.Middleware;
import io.flowcatalyst.framework.pipeline.Pipeline;
import java.util.ArrayList;
import java.util.Collection;
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

  private record Binding(String registrant, Operation<?, ?> operation, boolean contextMismatch) {}

  /** State shared by every view. */
  private static final class State {
    final @Nullable String environment;
    final List<Registration> registrations = new ArrayList<>();
    final List<Middleware<Request, Response>> global = new ArrayList<>();
    final List<SpaMount> spas = new ArrayList<>();
    final List<Binding> bindings = new ArrayList<>();
    final List<Operation<?, ?>> declared = new ArrayList<>();
    boolean declarationsKnown;
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

  /**
   * Records operations as declared, so that compiling the table can check every declared operation
   * is bound exactly once and every bound one was declared. The HTTP module always passes what
   * modules declared with {@code OpenApi.declare}. A table built by hand ({@code new Routes()} in a
   * test) that is never given declarations does not check that bound operations were declared.
   *
   * @param operations the declared operations
   * @throws IllegalStateException when the server is already listening
   */
  public void declare(Collection<? extends Operation<?, ?>> operations) {
    synchronized (state) {
      requireOpen();
      state.declarationsKnown = true;
      state.declared.addAll(operations);
    }
  }

  /**
   * Binds a declared {@link Operation} to its handler (OpenAPI contract section 3): a route with
   * the operation's method and pattern that checks the permission, parses the parameters, decodes
   * and validates the body, and writes the {@link Outcome}. Problems (an operation that was not
   * declared, bound twice, or whose descriptor is wrong) fail start-up when the table is compiled.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param operation the operation
   * @param handler the handler
   * @throws IllegalStateException when the server is already listening
   */
  public <B, R> void bind(Operation<B, R> operation, OperationHandler<B, R> handler) {
    bind(operation, handler, RouteOptions.none());
  }

  /**
   * Binds an operation with route middleware (for example a rate limit). The operation decides
   * whether a context is required; options that require one for an operation that does not are a
   * start-up problem, because the document would call it public.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param operation the operation
   * @param handler the handler
   * @param options route middleware
   * @throws IllegalStateException when the server is already listening
   */
  public <B, R> void bind(Operation<B, R> operation, OperationHandler<B, R> handler, RouteOptions options) {
    synchronized (state) {
      requireOpen();
      boolean already = false;
      for (Binding b : state.bindings) {
        already |= b.operation() == operation;
      }
      boolean mismatch = options.needsContext() && !operation.requiresContext();
      state.bindings.add(new Binding(registrant, operation, mismatch));
      if (already || !operation.problems().isEmpty() || mismatch) {
        return; // reported when the table is compiled
      }
      RouteOptions route = operation.requiresContext() ? RouteOptions.requiresContext() : RouteOptions.none();
      for (Middleware<Request, Response> m : options.middleware()) {
        route = route.with(m);
      }
      state.registrations.add(
          new Registration(
              operation.method(),
              RoutePattern.parse(operation.pattern()),
              registrant,
              OperationRoute.handler(operation, handler),
              route));
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
    List<Binding> bindings;
    List<Operation<?, ?>> declared;
    boolean declarationsKnown;
    synchronized (state) {
      state.sealed = true;
      registrations = List.copyOf(state.registrations);
      global = List.copyOf(state.global);
      spas = List.copyOf(state.spas);
      bindings = List.copyOf(state.bindings);
      declared = List.copyOf(state.declared);
      declarationsKnown = state.declarationsKnown;
    }
    List<Router.Compiled> compiled = new ArrayList<>();
    List<Problem> problems = new ArrayList<>();
    checkOperations(declared, declarationsKnown, bindings, problems);
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

  /** The operation rules of OpenAPI contract section 3. */
  private static void checkOperations(
      List<Operation<?, ?>> declared, boolean declarationsKnown, List<Binding> bindings, List<Problem> problems) {
    Map<String, Operation<?, ?>> ids = new LinkedHashMap<>();
    for (Operation<?, ?> op : declared) {
      String id = op.id();
      if (id == null) {
        continue;
      }
      Operation<?, ?> first = ids.putIfAbsent(id, op);
      if (first != null && first != op) {
        problems.add(
            Problem.of(
                null,
                "operation " + id,
                "two operations have the id '" + id + "': " + first.method() + " " + first.pattern()
                    + " and " + op.method() + " " + op.pattern()));
      }
    }
    java.util.Set<Operation<?, ?>> bound = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (Binding b : bindings) {
      Operation<?, ?> op = b.operation();
      String key = "operation " + op.label();
      if (!bound.add(op)) {
        problems.add(Problem.of(b.registrant(), key, "operation " + op.label() + " is bound twice"));
        continue;
      }
      if (declarationsKnown && !containsIdentity(declared, op)) {
        problems.add(
            Problem.of(
                b.registrant(),
                key,
                "operation " + op.label() + " is bound but was not declared (OpenApi.declare in register)"));
      }
      for (String p : op.problems()) {
        problems.add(Problem.of(b.registrant(), key, "operation " + op.label() + ": " + p));
      }
      if (b.contextMismatch()) {
        problems.add(
            Problem.of(
                b.registrant(),
                key,
                "operation " + op.label() + " is bound with options that require a context, but the"
                    + " operation does not (declare it with requiresContext() or permission(...))"));
      }
    }
    for (Operation<?, ?> op : declared) {
      if (!bound.contains(op)) {
        problems.add(
            Problem.of(
                null,
                "operation " + op.label(),
                "operation " + op.label() + " is declared but not bound (Routes.bind in boot)"));
      }
    }
  }

  private static boolean containsIdentity(List<Operation<?, ?>> list, Operation<?, ?> op) {
    for (Operation<?, ?> o : list) {
      if (o == op) {
        return true;
      }
    }
    return false;
  }
}
