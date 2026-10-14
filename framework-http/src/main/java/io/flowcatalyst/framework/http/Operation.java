package io.flowcatalyst.framework.http;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A typed JSON operation (OpenAPI contract sections 2 and 3): a value written once, declared in
 * {@code register} with {@code OpenApi.declare} and bound to a handler in {@code boot} with {@link
 * Routes#bind}. The router serves it like any route; the OpenAPI document is written from it.
 *
 * <pre>{@code
 * static final PathParam<String> ID = PathParam.string("id");
 *
 * static final Operation<NoBody, OrderView> READ =
 *     Operation.get("/api/orders/{id}", OrderView.class)
 *         .id("getOrder").summary("Read an order").tag("orders")
 *         .path(ID).permission("orders:order:read")
 *         .build();
 *
 * static final Operation<PlaceOrderBody, OrderView> PLACE =
 *     Operation.post("/api/orders", PlaceOrderBody.V, OrderView.class)
 *         .id("placeOrder").tag("orders").status(201).permission("orders:order:manage")
 *         .error(409, "ORDER_EXISTS", "an order with that reference exists")
 *         .build();
 * }</pre>
 *
 * <p>{@link Builder#build()} never throws for a descriptor mistake (a bad id, a path parameter
 * missing from the pattern, a {@code 204} with a response body): those are listed by {@link
 * #problems()} and reported as start-up problems when the routes are compiled, and as dump problems
 * by the {@code openapi} command.
 *
 * @param <B> the body type ({@link NoBody} when there is none)
 * @param <R> the response type ({@link NoBody} for {@code 204})
 */
public final class Operation<B, R> {

  private static final Pattern ID = Pattern.compile("[a-z][A-Za-z0-9]*");
  private static final Pattern TAG = Pattern.compile("[a-z][a-z0-9-]*");
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
  private static final Set<Integer> SUCCESS = Set.of(200, 201, 202, 204);

  /**
   * An expected failure the operation documents.
   *
   * @param status the HTTP status, 400 to 599
   * @param code the {@code error} code
   * @param message what it means
   */
  public record DeclaredError(int status, String code, String message) {}

  private final Method method;
  private final String pattern;
  private final @Nullable Validator<B> body;
  private final Type response;
  private final @Nullable String id;
  private final @Nullable String summary;
  private final @Nullable String description;
  private final List<String> tags;
  private final List<PathParam<?>> pathParams;
  private final List<QueryParam<?>> queryParams;
  private final int status;
  private final boolean requiresContext;
  private final @Nullable String permission;
  private final List<DeclaredError> errors;
  private final List<String> problems;

  private Operation(Builder<B, R> b) {
    this.method = b.method;
    this.pattern = b.pattern;
    this.body = b.body;
    this.response = b.response;
    this.id = b.id;
    this.summary = b.summary;
    this.description = b.description;
    this.tags = List.copyOf(b.tags);
    this.pathParams = List.copyOf(b.pathParams);
    this.queryParams = List.copyOf(b.queryParams);
    this.status = b.status != 0 ? b.status : (b.response == NoBody.class ? 204 : 200);
    this.permission = b.permission;
    this.requiresContext = b.requiresContext || b.permission != null;
    this.errors = List.copyOf(b.errors);
    this.problems = List.copyOf(check(b));
  }

  // ---- factories -----------------------------------------------------------------------------

  /**
   * A {@code GET} operation.
   *
   * @param <R> the response type
   * @param pattern the route pattern, for example {@code /api/orders/{id}}
   * @param response a record class, or {@code NoBody.class} for {@code 204}
   * @return the builder
   */
  public static <R> Builder<NoBody, R> get(String pattern, Class<R> response) {
    return new Builder<>(Method.GET, pattern, null, response);
  }

  /**
   * A {@code GET} operation with a generic response type.
   *
   * @param <R> the response type
   * @param pattern the route pattern
   * @param response the response type, for example {@code new TypeRef<CursorPage<OrderView>>() {}}
   * @return the builder
   */
  public static <R> Builder<NoBody, R> get(String pattern, TypeRef<R> response) {
    return new Builder<>(Method.GET, pattern, null, response.type());
  }

  /**
   * A {@code DELETE} operation.
   *
   * @param <R> the response type
   * @param pattern the route pattern
   * @param response a record class, or {@code NoBody.class} for {@code 204}
   * @return the builder
   */
  public static <R> Builder<NoBody, R> delete(String pattern, Class<R> response) {
    return new Builder<>(Method.DELETE, pattern, null, response);
  }

  /**
   * A {@code DELETE} operation with a generic response type.
   *
   * @param <R> the response type
   * @param pattern the route pattern
   * @param response the response type
   * @return the builder
   */
  public static <R> Builder<NoBody, R> delete(String pattern, TypeRef<R> response) {
    return new Builder<>(Method.DELETE, pattern, null, response.type());
  }

  /**
   * A {@code POST} operation with a JSON body.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param pattern the route pattern
   * @param body the body's validator, which also names its record class
   * @param response a record class, or {@code NoBody.class} for {@code 204}
   * @return the builder
   */
  public static <B, R> Builder<B, R> post(String pattern, Validator<B> body, Class<R> response) {
    return new Builder<>(Method.POST, pattern, body, response);
  }

  /**
   * A {@code POST} operation with a JSON body and a generic response type.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param pattern the route pattern
   * @param body the body's validator
   * @param response the response type
   * @return the builder
   */
  public static <B, R> Builder<B, R> post(String pattern, Validator<B> body, TypeRef<R> response) {
    return new Builder<>(Method.POST, pattern, body, response.type());
  }

  /**
   * A {@code POST} operation without a body. A body sent anyway is not read.
   *
   * @param <R> the response type
   * @param pattern the route pattern
   * @param response a record class, or {@code NoBody.class} for {@code 204}
   * @return the builder
   */
  public static <R> Builder<NoBody, R> post(String pattern, Class<R> response) {
    return new Builder<>(Method.POST, pattern, null, response);
  }

  /**
   * A {@code PUT} operation with a JSON body.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param pattern the route pattern
   * @param body the body's validator
   * @param response a record class, or {@code NoBody.class} for {@code 204}
   * @return the builder
   */
  public static <B, R> Builder<B, R> put(String pattern, Validator<B> body, Class<R> response) {
    return new Builder<>(Method.PUT, pattern, body, response);
  }

  /**
   * A {@code PUT} operation with a JSON body and a generic response type.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param pattern the route pattern
   * @param body the body's validator
   * @param response the response type
   * @return the builder
   */
  public static <B, R> Builder<B, R> put(String pattern, Validator<B> body, TypeRef<R> response) {
    return new Builder<>(Method.PUT, pattern, body, response.type());
  }

  /**
   * A {@code PATCH} operation with a JSON body.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param pattern the route pattern
   * @param body the body's validator
   * @param response a record class, or {@code NoBody.class} for {@code 204}
   * @return the builder
   */
  public static <B, R> Builder<B, R> patch(String pattern, Validator<B> body, Class<R> response) {
    return new Builder<>(Method.PATCH, pattern, body, response);
  }

  /**
   * A {@code PATCH} operation with a JSON body and a generic response type.
   *
   * @param <B> the body type
   * @param <R> the response type
   * @param pattern the route pattern
   * @param body the body's validator
   * @param response the response type
   * @return the builder
   */
  public static <B, R> Builder<B, R> patch(String pattern, Validator<B> body, TypeRef<R> response) {
    return new Builder<>(Method.PATCH, pattern, body, response.type());
  }

  // ---- reading -------------------------------------------------------------------------------

  /**
   * The method.
   *
   * @return {@code GET}, {@code PUT}, {@code POST}, {@code DELETE} or {@code PATCH}
   */
  public Method method() {
    return method;
  }

  /**
   * The route pattern.
   *
   * @return the pattern, for example {@code /api/orders/{id}}
   */
  public String pattern() {
    return pattern;
  }

  /**
   * The operation id, which becomes the TypeScript function name.
   *
   * @return the id, or null when none was given (a problem)
   */
  public @Nullable String id() {
    return id;
  }

  /**
   * A name for messages: the id, or the method and pattern when there is none.
   *
   * @return the label
   */
  public String label() {
    return id != null ? id : method + " " + pattern;
  }

  /**
   * The summary.
   *
   * @return the summary, or null
   */
  public @Nullable String summary() {
    return summary;
  }

  /**
   * The description.
   *
   * @return the description, or null
   */
  public @Nullable String description() {
    return description;
  }

  /**
   * The tags.
   *
   * @return the tags in the order given
   */
  public List<String> tags() {
    return tags;
  }

  /**
   * The path parameters.
   *
   * @return in the order declared
   */
  public List<PathParam<?>> pathParams() {
    return pathParams;
  }

  /**
   * The query parameters.
   *
   * @return in the order declared
   */
  public List<QueryParam<?>> queryParams() {
    return queryParams;
  }

  /**
   * The body's validator.
   *
   * @return the validator, or null when the operation has no body
   */
  public @Nullable Validator<B> body() {
    return body;
  }

  /**
   * The response type.
   *
   * @return a class (a record, a sealed interface or {@code NoBody.class}) or a parameterised type
   */
  public Type responseType() {
    return response;
  }

  /**
   * Whether the success answer has a body.
   *
   * @return false when the response is {@link NoBody}
   */
  public boolean hasResponseBody() {
    return response != NoBody.class;
  }

  /**
   * The success status.
   *
   * @return {@code 200} by default, {@code 204} for a {@link NoBody} response, or the one given
   */
  public int status() {
    return status;
  }

  /**
   * Whether the operation needs a {@code Context}.
   *
   * @return true after {@code requiresContext()} or {@code permission(...)}
   */
  public boolean requiresContext() {
    return requiresContext;
  }

  /**
   * The permission the context must hold.
   *
   * @return the permission, or null
   */
  public @Nullable String permission() {
    return permission;
  }

  /**
   * The documented expected failures.
   *
   * @return in the order declared
   */
  public List<DeclaredError> errors() {
    return errors;
  }

  /**
   * The error statuses the document lists for this operation (contract section 6): {@code 400}
   * with a body or any parameter, {@code 401} and {@code 403} with a context, {@code 413} and
   * {@code 415} with a body, and every declared one. {@code default} covers the rest.
   *
   * @return the statuses, ascending
   */
  public Set<Integer> documentedErrorStatuses() {
    Set<Integer> out = new TreeSet<>();
    if (body != null || !pathParams.isEmpty() || !queryParams.isEmpty()) {
      out.add(400);
    }
    if (requiresContext) {
      out.add(401);
      out.add(403);
    }
    if (body != null) {
      out.add(413);
      out.add(415);
    }
    for (DeclaredError e : errors) {
      out.add(e.status());
    }
    return Collections.unmodifiableSet(out);
  }

  /**
   * What is wrong with this descriptor; empty when nothing is.
   *
   * @return the problems, each a sentence
   */
  public List<String> problems() {
    return problems;
  }

  @Override
  public String toString() {
    return "Operation[" + label() + ": " + method + " " + pattern + "]";
  }

  private static List<String> check(Builder<?, ?> b) {
    List<String> out = new ArrayList<>();
    if (b.id == null) {
      out.add("an operation needs an id (.id(\"listOrders\"))");
    } else if (!ID.matcher(b.id).matches()) {
      out.add("operation id '" + b.id + "' must match [a-z][A-Za-z0-9]*");
    }
    for (String tag : b.tags) {
      if (!TAG.matcher(tag).matches()) {
        out.add("tag '" + tag + "' must match [a-z][a-z0-9-]*");
      }
    }
    List<String> patternNames = List.of();
    try {
      patternNames = RoutePattern.parse(b.pattern).paramNames();
    } catch (IllegalArgumentException e) {
      out.add(e.getMessage());
    }
    Set<String> declared = new HashSet<>();
    for (PathParam<?> p : b.pathParams) {
      if (!declared.add(p.name())) {
        out.add("path parameter '" + p.name() + "' is declared twice");
      } else if (!patternNames.contains(p.name())) {
        out.add("path parameter '" + p.name() + "' is not in the pattern " + b.pattern);
      }
    }
    for (String name : patternNames) {
      if (!declared.contains(name)) {
        out.add("{" + name + "} in the pattern " + b.pattern + " is not declared (.path(PathParam...))");
      }
    }
    Set<String> queries = new HashSet<>();
    for (QueryParam<?> q : b.queryParams) {
      if (!queries.add(q.name())) {
        out.add("query parameter '" + q.name() + "' is declared twice");
      }
    }
    boolean noContent = b.response == NoBody.class;
    if (b.status != 0 && !SUCCESS.contains(b.status)) {
      out.add("the success status is 200, 201, 202 or 204, not " + b.status);
    } else if (b.status == 204 && !noContent) {
      out.add("status 204 needs a NoBody response");
    } else if (noContent && b.status != 0 && b.status != 204) {
      out.add("a NoBody response is answered 204, not " + b.status);
    }
    if (b.permission != null && b.permission.isBlank()) {
      out.add("the permission must not be blank");
    }
    Set<String> errorKeys = new HashSet<>();
    for (DeclaredError e : b.errors) {
      if (e.status() < 400 || e.status() > 599) {
        out.add("error " + e.code() + " has status " + e.status() + "; an error status is 400 to 599");
      }
      if (!CODE.matcher(e.code()).matches()) {
        out.add("error code '" + e.code() + "' must match [A-Z][A-Z0-9_]*");
      }
      if (!errorKeys.add(e.status() + " " + e.code())) {
        out.add("error " + e.status() + " " + e.code() + " is declared twice");
      }
      if (e.message().isBlank()) {
        out.add("error " + e.code() + " needs a message");
      }
    }
    return out;
  }

  /**
   * Builds an {@link Operation}. Not thread-safe; {@link #build()} returns an immutable value.
   *
   * @param <B> the body type
   * @param <R> the response type
   */
  public static final class Builder<B, R> {

    private final Method method;
    private final String pattern;
    private final @Nullable Validator<B> body;
    private final Type response;
    private @Nullable String id;
    private @Nullable String summary;
    private @Nullable String description;
    private final List<String> tags = new ArrayList<>();
    private final List<PathParam<?>> pathParams = new ArrayList<>();
    private final List<QueryParam<?>> queryParams = new ArrayList<>();
    private int status;
    private boolean requiresContext;
    private @Nullable String permission;
    private final List<DeclaredError> errors = new ArrayList<>();

    private Builder(Method method, String pattern, @Nullable Validator<B> body, Type response) {
      this.method = method;
      this.pattern = Objects.requireNonNull(pattern);
      this.body = body;
      this.response = Objects.requireNonNull(response);
    }

    /**
     * The operation id: {@code [a-z][A-Za-z0-9]*}, unique in the application; it becomes the
     * TypeScript function name. Required.
     *
     * @param value the id
     * @return this builder
     */
    public Builder<B, R> id(String value) {
      this.id = value;
      return this;
    }

    /**
     * A one-line summary.
     *
     * @param value the summary
     * @return this builder
     */
    public Builder<B, R> summary(String value) {
      this.summary = value;
      return this;
    }

    /**
     * A longer description.
     *
     * @param value the description
     * @return this builder
     */
    public Builder<B, R> description(String value) {
      this.description = value;
      return this;
    }

    /**
     * Adds tags, {@code [a-z][a-z0-9-]*}.
     *
     * @param values the tags
     * @return this builder
     */
    public Builder<B, R> tag(String... values) {
      tags.addAll(List.of(values));
      return this;
    }

    /**
     * Declares path parameters; every {@code {name}} of the pattern needs one.
     *
     * @param params the parameters
     * @return this builder
     */
    public Builder<B, R> path(PathParam<?>... params) {
      pathParams.addAll(List.of(params));
      return this;
    }

    /**
     * Declares query parameters, documented in this order.
     *
     * @param params the parameters
     * @return this builder
     */
    public Builder<B, R> query(QueryParam<?>... params) {
      queryParams.addAll(List.of(params));
      return this;
    }

    /**
     * The success status: {@code 200} (default), {@code 201}, {@code 202}, or {@code 204} (the
     * default, and the only one, for a {@link NoBody} response).
     *
     * @param value the status
     * @return this builder
     */
    public Builder<B, R> status(int value) {
      this.status = value;
      return this;
    }

    /**
     * The operation needs a {@code Context} (as {@code RouteOptions.requiresContext()}): the
     * document lists the security schemes and {@code 401} and {@code 403}.
     *
     * @return this builder
     */
    public Builder<B, R> requiresContext() {
      this.requiresContext = true;
      return this;
    }

    /**
     * The permission the context must hold ({@code Context.hasPermission}, wildcards allowed in the
     * held permission); otherwise {@code 403 FORBIDDEN}. Implies {@link #requiresContext()}.
     * Documented as {@code x-fw-permission}.
     *
     * @param value the permission, for example {@code orders:order:manage}
     * @return this builder
     */
    public Builder<B, R> permission(String value) {
      this.permission = value;
      return this;
    }

    /**
     * Documents an expected failure; repeatable. The codes of one status are listed in its
     * description.
     *
     * @param status the HTTP status, 400 to 599
     * @param code the {@code error} code, {@code [A-Z][A-Z0-9_]*}
     * @param message what it means
     * @return this builder
     */
    public Builder<B, R> error(int status, String code, String message) {
      errors.add(new DeclaredError(status, code, message));
      return this;
    }

    /**
     * Builds the operation. Never throws for a descriptor mistake: see {@link
     * Operation#problems()}.
     *
     * @return the operation
     */
    public Operation<B, R> build() {
      return new Operation<>(this);
    }
  }
}
