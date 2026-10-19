package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.config.Config;
import io.flowcatalyst.framework.config.Secret;
import io.flowcatalyst.framework.config.Setting;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Lookup;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.Provider;
import io.flowcatalyst.framework.http.ContextResolver;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.ratelimit.KeyResolver;
import io.flowcatalyst.framework.http.ratelimit.KeyResolvers;
import io.flowcatalyst.framework.http.ratelimit.MemoryRateLimiter;
import io.flowcatalyst.framework.http.ratelimit.RateLimit;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.net.URI;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sessions and login (contract {@code sessions-and-login.md}): the application as its own
 * backend-for-frontend and an OIDC relying party of the FlowCatalyst platform.
 *
 * <p>Adds {@code GET /auth/login}, {@code GET /auth/callback}, {@code GET /auth/session} and {@code
 * POST /auth/logout} (the last two as typed operations {@code getAuthSession} and {@code logout},
 * with the {@code session} and, when bearer tokens are accepted, {@code bearer} security schemes in
 * the OpenAPI document); provides {@link ContextResolver} (an {@link AuthContextResolver}), {@link
 * Sessions} and {@link PlatformTokens}. Configuration holds only connections and secrets ({@code
 * auth.issuer}, {@code auth.client-id}, {@code auth.client-secret}, {@code auth.public-base-url},
 * {@code auth.cookie-keys}); everything else is set here, in code.
 *
 * <pre>{@code
 * App.builder()
 *     .module(new JettyHttpModule())
 *     .module(new DatabaseModule())
 *     .module(new AuthDatabaseModule())          // the default session store
 *     .module(AuthModule.builder()
 *         .application(new ApplicationCode("ingestor"))
 *         .permissions(PermissionMapper.scopes().and(PermissionMapper.byRole(Map.of(
 *             "ingestor:operator", Set.of("ingestor:ingestion:manage")))))
 *         .build())
 * }</pre>
 */
public final class AuthModule implements Module {

  /** The module id. */
  public static final String ID = "auth";

  /** The platform's base URL, which is also the issuer of its tokens. */
  public static final Setting<URI> ISSUER =
      Setting.uri("auth.issuer").asRequired().describedAs("The FlowCatalyst platform's base URL; the issuer of its tokens.");

  /** The OAuth client id of the application's login client. */
  public static final Setting<String> CLIENT_ID =
      Setting.string("auth.client-id").asRequired().describedAs("The OAuth client id of this application's login client.");

  /** The login client's secret; unset for a PUBLIC client. */
  public static final Setting<Secret> CLIENT_SECRET =
      Setting.secret("auth.client-secret")
          .describedAs("The login client's secret for a CONFIDENTIAL client; leave unset for a PUBLIC client.");

  /** The application's public origin as browsers see it. */
  public static final Setting<URI> PUBLIC_BASE_URL =
      Setting.uri("auth.public-base-url")
          .asRequired()
          .describedAs("This application's public base URL, for example https://ingest.example.com.");

  /** The keys of the cookie session store. */
  public static final Setting<Secret> COOKIE_KEYS =
      Setting.secret("auth.cookie-keys")
          .describedAs("Keys that seal session cookies: <kid>:<base64url of 32 bytes>, comma-separated, the sealing key first.");

  private static final Logger LOG = LoggerFactory.getLogger(AuthModule.class);

  private final Builder b;

  private AuthModule(Builder builder) {
    this.b = builder;
  }

  /**
   * Starts a module definition.
   *
   * @return the builder
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    // Configuration holds connections and secrets only; a misspelt auth key fails start-up
    // (config-and-code contract, section 5).
    app.claimComponent("auth");
    app.declare(ISSUER);
    app.declare(CLIENT_ID);
    app.declare(CLIENT_SECRET);
    app.declare(PUBLIC_BASE_URL);
    app.declare(COOKIE_KEYS);
    app.provide(Services.class, this::services);
    app.provide(PlatformTokens.class, lookup -> lookup.get(Services.class).tokens());
    app.provide(Sessions.class, lookup -> lookup.get(Services.class).sessions());
    app.provide(AuthContextResolver.class, lookup -> lookup.get(Services.class).resolver());
    if (b.provideResolver) {
      app.provide(ContextResolver.class, lookup -> lookup.get(AuthContextResolver.class));
    }
    // GET /auth/session and POST /auth/logout are typed operations (contract section 4.5)
    AuthApi.declare(app, b.bearer);
  }

  @Override
  public void boot(App app) {
    Services s = app.get(Services.class);
    Routes routes = app.get(Routes.class).forModule(ID);
    routes.use(new AuthMiddleware(b.loginFailure));
    Middleware<Request, Response> limit = b.rateLimit;
    if (limit == null && b.rateLimited) {
      limit =
          RateLimit.by(
                  MemoryRateLimiter.create("auth", new RateLimitPolicy(20, Duration.ofMinutes(1), 20)),
                  warnOnForwardedFor(KeyResolvers.clientAddress()))
              .build();
    }
    RouteOptions publicRoutes = limit == null ? RouteOptions.none() : RouteOptions.none().with(limit);
    routes.get("/auth/login", s.routes()::login, publicRoutes);
    routes.get("/auth/callback", s.routes()::callback, publicRoutes);
    routes.bind(AuthApi.SESSION, s.routes()::session);
    routes.bind(AuthApi.LOGOUT, s.routes()::logout);
  }

  /**
   * The default limiter keys on the TCP peer, which behind a proxy is the proxy itself: every user
   * then shares one bucket. One WARN, the first time a request carries {@code X-Forwarded-For}, makes
   * the mistake visible (security review I3; contract section 8).
   */
  private static KeyResolver warnOnForwardedFor(KeyResolver peer) {
    AtomicBoolean warned = new AtomicBoolean();
    return request -> {
      if (request.header("X-Forwarded-For") != null && warned.compareAndSet(false, true)) {
        LOG.warn(
            "a request carried X-Forwarded-For, but the sign-in rate limiter keys on the TCP peer: behind a proxy every"
                + " user shares one bucket; pass rateLimit(RateLimit.by(limiter, KeyResolvers.forwardedFor(n)).build())");
      }
      return peer.key(request);
    };
  }

  /** Everything the module builds, once. */
  record Services(PlatformTokens tokens, Sessions sessions, AuthContextResolver resolver, AuthRoutes routes) {}

  private Services services(Lookup lookup) throws Exception {
    Config config = lookup.get(Config.class);
    InstantSource clock = lookup.get(InstantSource.class);
    URI issuer = config.get(ISSUER);
    URI base = config.get(PUBLIC_BASE_URL);
    String env = config.environment();
    boolean development = env.equals("local") || env.equals("test");
    checkUrl("auth.issuer", issuer, development);
    checkUrl("auth.public-base-url", base, development);
    String basePath = base.getPath();
    if ((basePath != null && !basePath.isEmpty() && !basePath.equals("/")) || base.getQuery() != null || base.getFragment() != null) {
      throw new IllegalArgumentException("auth.public-base-url must be an origin with no path, query or fragment: " + base);
    }
    boolean secure = base.getScheme().equalsIgnoreCase("https");
    if (!secure) {
      LOG.warn(
          "SESSIONS OVER PLAIN HTTP ({}): development only; cookies are sent without Secure and without the __Host- prefix",
          base);
    }
    if (issuer.getScheme().equalsIgnoreCase("http")) {
      LOG.warn("the platform ({}) is reached over plain http: development only", issuer);
    }
    String clientId = config.get(CLIENT_ID);
    // a blank value means no secret: a PUBLIC client (the same as the key being unset)
    Secret secret = config.find(CLIENT_SECRET).filter(v -> !v.reveal().isBlank()).orElse(null);
    SessionStore store = store(lookup, config, clock);
    String origin = CsrfGuard.origin(base);

    Platform platform = new Platform(issuer, b.httpTimeout, clock, development);
    PlatformTokens tokens = new PlatformTokens(platform, clock);
    RelyingParty rp = new RelyingParty(platform, clientId, secret, origin + "/auth/callback", b.scope, b.loginTheme, clock);
    Cookies cookies = new Cookies(secure);
    Authorities authorities = new Authorities(b.permissions, b.clients, b.application);
    SessionManager manager = new SessionManager(store, clock, b.idleTimeout, b.absoluteTimeout);
    Sessions sessions = new Sessions(manager, cookies, CsrfGuard.forSessions(base), authorities, clock);
    AuthContextResolver resolver = new AuthContextResolver(sessions, b.bearer ? tokens : null, authorities);
    String postLogout = b.postLogoutPath == null ? null : origin + b.postLogoutPath;
    AuthRoutes routes =
        new AuthRoutes(rp, manager, sessions, cookies, b.returnTo, b.defaultLanding, b.loginFailure, postLogout, clock);
    if (!store.revocable()) {
      LOG.info("session store keeps nothing on the server: logout cannot revoke a copied session cookie");
    }
    return new Services(tokens, sessions, resolver, routes);
  }

  private SessionStore store(Lookup lookup, Config config, InstantSource clock) throws Exception {
    if (b.cookieSessions) {
      Secret keys =
          config.find(COOKIE_KEYS)
              .orElseThrow(() -> new IllegalArgumentException("the cookie session store needs auth.cookie-keys"));
      return CookieSessionStore.create(CookieKeys.parse(keys), clock);
    }
    Provider<? extends SessionStore> provider = b.sessions;
    if (provider != null) {
      SessionStore store = provider.create(lookup);
      if (store == null) {
        throw new IllegalStateException("the session store provider returned null");
      }
      return store;
    }
    // the default: the database store, provided by AuthDatabaseModule
    return lookup.get(SessionStore.class);
  }

  private static void checkUrl(String key, URI uri, boolean development) {
    String scheme = uri.getScheme();
    if (scheme == null || uri.getHost() == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
      throw new IllegalArgumentException(key + " must be an absolute http(s) URL: " + uri);
    }
    if (scheme.equalsIgnoreCase("http") && !development) {
      throw new IllegalStateException(
          key + " is plain http, which is allowed only when APP_ENV is local or test; session cookies would travel in clear text");
    }
  }

  /** The module's behaviour, set in code. */
  public static final class Builder {

    private @Nullable Provider<? extends SessionStore> sessions;
    private boolean cookieSessions;
    private Duration idleTimeout = Duration.ofMinutes(30);
    private Duration absoluteTimeout = Duration.ofHours(8);
    private PermissionMapper permissions = PermissionMapper.scopes();
    private ClientSelector clients = ClientSelector.homeClient();
    private @Nullable ApplicationCode application;
    private String defaultLanding = "/";
    private ReturnToPolicy returnTo = ReturnToPolicy.sameOriginPath();
    private @Nullable String postLogoutPath;
    private LoginFailureHandler loginFailure = LoginFailureHandler.json();
    private String scope = "openid profile email";
    private @Nullable String loginTheme;
    private @Nullable Middleware<Request, Response> rateLimit;
    private boolean rateLimited = true;
    private boolean bearer = true;
    private Duration httpTimeout = Duration.ofSeconds(10);
    private boolean provideResolver = true;

    private Builder() {}

    /**
     * The session store, built when the application starts. Without this (and without {@link
     * #cookieSessions()}) the store is the provided {@link SessionStore}: list {@code
     * AuthDatabaseModule} for the default {@code database} store.
     *
     * <pre>{@code
     * .sessions(lookup -> RedisSessionStore.create(lookup.get(RedisConnections.class).defaultInstance(), lookup.get(InstantSource.class)))
     * .sessions(lookup -> MemorySessionStore.create(lookup.get(InstantSource.class)))   // tests, one process
     * }</pre>
     *
     * @param provider builds the store
     * @return this builder
     */
    public Builder sessions(Provider<? extends SessionStore> provider) {
      this.sessions = provider;
      this.cookieSessions = false;
      return this;
    }

    /**
     * Sessions sealed in the cookie, with the keys of {@code auth.cookie-keys}. Nothing is kept on
     * the server, so logout cannot revoke a copied cookie (contract section 6.5).
     *
     * @return this builder
     */
    public Builder cookieSessions() {
      this.cookieSessions = true;
      this.sessions = null;
      return this;
    }

    /**
     * How long a session lasts without activity. Default 30 minutes.
     *
     * @param value positive
     * @return this builder
     */
    public Builder idleTimeout(Duration value) {
      this.idleTimeout = positive(value, "idleTimeout");
      return this;
    }

    /**
     * How long a session lasts at most. Default 8 hours.
     *
     * @param value positive
     * @return this builder
     */
    public Builder absoluteTimeout(Duration value) {
      this.absoluteTimeout = positive(value, "absoluteTimeout");
      return this;
    }

    /**
     * How a principal's permissions are found. Default {@link PermissionMapper#scopes()}: a
     * bearer token's {@code scope}, nothing for a browser session.
     *
     * @param value the mapping
     * @return this builder
     */
    public Builder permissions(PermissionMapper value) {
      this.permissions = value;
      return this;
    }

    /**
     * How the client of a request is chosen. Default {@link ClientSelector#homeClient()}.
     *
     * @param value the selector
     * @return this builder
     */
    public Builder clients(ClientSelector value) {
      this.clients = value;
      return this;
    }

    /**
     * The application code every {@code Context} carries.
     *
     * @param value the code
     * @return this builder
     */
    public Builder application(ApplicationCode value) {
      this.application = value;
      return this;
    }

    /**
     * Where a browser lands after signing in when no usable {@code returnTo} was given. Default
     * {@code /}.
     *
     * @param path a same-origin path
     * @return this builder
     */
    public Builder defaultLanding(String path) {
      if (!ReturnToPolicy.isSameOriginPath(path)) {
        throw new IllegalArgumentException("the default landing must be a same-origin path: " + path);
      }
      this.defaultLanding = path;
      return this;
    }

    /**
     * Decides where to land after signing in. Default {@link ReturnToPolicy#sameOriginPath()}.
     *
     * @param value the policy
     * @return this builder
     */
    public Builder returnTo(ReturnToPolicy value) {
      this.returnTo = value;
      return this;
    }

    /**
     * Where the platform sends the browser after single logout; the logout answer's {@code
     * endSessionUrl} names it. It must be registered as a post-logout redirect URI of the
     * platform's login client. Default none.
     *
     * @param path a same-origin path, made absolute with {@code auth.public-base-url}
     * @return this builder
     */
    public Builder postLogoutRedirect(String path) {
      if (!ReturnToPolicy.isSameOriginPath(path)) {
        throw new IllegalArgumentException("the post-logout redirect must be a same-origin path: " + path);
      }
      this.postLogoutPath = path;
      return this;
    }

    /**
     * How a failed sign-in is answered. Default {@link LoginFailureHandler#json()}.
     *
     * @param value the handler
     * @return this builder
     */
    public Builder loginFailure(LoginFailureHandler value) {
      this.loginFailure = value;
      return this;
    }

    /**
     * The scopes requested. Default {@code openid profile email}; must contain {@code openid}.
     * {@code offline_access} is refused: the session keeps no refresh token (contract section
     * 6.4).
     *
     * @param value space-separated scopes
     * @return this builder
     */
    public Builder scope(String value) {
      java.util.List<String> scopes = java.util.Arrays.asList(value.trim().split("\\s+"));
      if (!scopes.contains("openid")) {
        throw new IllegalArgumentException("the scope must contain openid");
      }
      if (scopes.contains("offline_access")) {
        throw new IllegalArgumentException("offline_access is not supported: sessions keep no refresh token");
      }
      this.scope = String.join(" ", scopes);
      return this;
    }

    /**
     * The platform's login theme hint ({@code client=} on the authorize URL).
     *
     * @param identifier a client identifier, {@code [a-z0-9]([a-z0-9-]*[a-z0-9])?}, at most 64
     * @return this builder
     */
    public Builder loginTheme(String identifier) {
      if (identifier.length() > 64 || !identifier.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?")) {
        throw new IllegalArgumentException("a login theme is a client identifier: " + identifier);
      }
      this.loginTheme = identifier;
      return this;
    }

    /**
     * Replaces the rate limit on {@code /auth/login} and {@code /auth/callback}. Default: a memory
     * limiter named {@code auth}, 20 a minute per client address, burst 20.
     *
     * <pre>{@code
     * .rateLimit(RateLimit.by(RedisRateLimiter.create("auth", redis, RateLimitPolicy.of(20, Duration.ofMinutes(1))),
     *     KeyResolvers.forwardedFor(1)).build())
     * }</pre>
     *
     * @param middleware the rate-limit middleware
     * @return this builder
     */
    public Builder rateLimit(Middleware<Request, Response> middleware) {
      this.rateLimit = middleware;
      this.rateLimited = true;
      return this;
    }

    /**
     * No rate limit on the public routes (for example when a proxy in front limits them).
     *
     * @return this builder
     */
    public Builder noRateLimit() {
      this.rateLimit = null;
      this.rateLimited = false;
      return this;
    }

    /**
     * Whether {@code Authorization: Bearer} platform access tokens are accepted. Default true.
     *
     * @param value false for browser sessions only
     * @return this builder
     */
    public Builder bearerTokens(boolean value) {
      this.bearer = value;
      return this;
    }

    /**
     * How long to wait for the platform (discovery, keys, the token endpoint). Default 10 seconds.
     *
     * @param value positive
     * @return this builder
     */
    public Builder httpTimeout(Duration value) {
      this.httpTimeout = positive(value, "httpTimeout");
      return this;
    }

    /**
     * Whether to provide the {@link ContextResolver}. Default true; false when the application
     * provides its own, which can delegate to the provided {@link AuthContextResolver}.
     *
     * @param value false to provide only {@link AuthContextResolver}
     * @return this builder
     */
    public Builder provideContextResolver(boolean value) {
      this.provideResolver = value;
      return this;
    }

    /**
     * Builds the module.
     *
     * @return the module
     */
    public AuthModule build() {
      if (idleTimeout.compareTo(absoluteTimeout) > 0) {
        throw new IllegalArgumentException("the idle timeout is longer than the absolute timeout");
      }
      Builder copy = new Builder();
      copy.sessions = sessions;
      copy.cookieSessions = cookieSessions;
      copy.idleTimeout = idleTimeout;
      copy.absoluteTimeout = absoluteTimeout;
      copy.permissions = permissions;
      copy.clients = clients;
      copy.application = application;
      copy.defaultLanding = defaultLanding;
      copy.returnTo = returnTo;
      copy.postLogoutPath = postLogoutPath;
      copy.loginFailure = loginFailure;
      copy.scope = scope;
      copy.loginTheme = loginTheme;
      copy.rateLimit = rateLimit;
      copy.rateLimited = rateLimited;
      copy.bearer = bearer;
      copy.httpTimeout = httpTimeout;
      copy.provideResolver = provideResolver;
      return new AuthModule(copy);
    }

    private static Duration positive(Duration value, String name) {
      if (value.isNegative() || value.isZero()) {
        throw new IllegalArgumentException(name + " must be positive");
      }
      return value;
    }
  }
}
