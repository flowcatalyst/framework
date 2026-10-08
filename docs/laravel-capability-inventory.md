# Laravel capability inventory

**Scope.** A reference on what the Laravel framework provides, how each part is structured, and which of its ideas carry over to a statically typed application framework (Java 25, Rust, possibly TypeScript) for business services with strong domain models. It is the factual base for a design; it is not the design. Eloquent/active record and runtime magic are covered because they must be understood in order to be dropped deliberately.

**Version examined.** Laravel framework **v13.17.0** (`Illuminate\Foundation\Application::VERSION`), PHP requirement 8.3 to 8.5 (release notes: Laravel 13 released 17 March 2026, bug fixes until Q3 2027, security fixes until 17 March 2028). Source read from the local install in `laravel-flowcatalyst-example/vendor/laravel/framework/src/Illuminate/`. Docs read at laravel.com/docs (13.x).

**Method.** "Verified in source" means I read the file in the installed v13.17.0. "Verified in docs" means I read the 13.x documentation page. Anything else is labelled "not verified".

**What I could not verify (read this first).**

1. First-party packages not installed locally were checked against docs only, not source: Pennant, Sanctum, Telescope, Pulse, Nightwatch, Scout, Fortify (via the starter-kit page), Precognition client libraries, Inertia, Vite. The Pennant driver contract is quoted from the docs.
2. Not verified at all (listed from the documentation footer / general knowledge only): Passport, Socialite, Cashier, Horizon, Reverb, Livewire internals, Folio, Volt, Dusk, Sail, Boost/MCP, Wayfinder, Laravel Cloud, Forge, Vapor, Nova. Statements about them are marked "not verified".
3. Slack and Vonage notification channels are separate packages (`laravel/slack-notification-channel`, `laravel/vonage-notification-channel`); their source was not read. The channel contract is verified from the docs and from the in-core `DatabaseChannel`.
4. Flysystem (the library under the filesystem) and Symfony Mailer / Symfony Console / Monolog (libraries under mail, console, logging) were not read; only Laravel's wrapping of them.
5. Eloquent internals were inspected only at file-list level plus a few static methods (`preventLazyLoading`, `shouldBeStrict`). Descriptions of relationships, casts, observers and soft deletes are from the file layout and general knowledge.
6. The "commonly said" criticisms and praise in Part 3 are a summary of community discourse, not citations; I did not retrieve sources for them.
7. The example app's `public/index.php` begins with a stray `echo phpinfo();die;` line (observed, read-only, not touched). It is unrelated to the framework but would stop the app serving requests.

---

# Part 1. The skeleton

## 1.1 Application lifecycle

### Entry points

The example app (`laravel-flowcatalyst-example`) shows the whole layout. Two entry scripts, both tiny:

```php
// public/index.php (HTTP)
require __DIR__.'/../vendor/autoload.php';
$app = require_once __DIR__.'/../bootstrap/app.php';
$app->handleRequest(Request::capture());

// artisan (console)
$app = require_once __DIR__.'/bootstrap/app.php';
$status = $app->handleCommand(new ArgvInput);
```

`bootstrap/app.php` builds the application with a fluent builder (`Application::configure(...)`, `Foundation/Configuration/ApplicationBuilder.php`):

```php
return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(web: __DIR__.'/../routes/web.php',
                  commands: __DIR__.'/../routes/console.php',
                  health: '/up')
    ->withMiddleware(function (Middleware $middleware): void { })
    ->withExceptions(function (Exceptions $exceptions): void {
        $exceptions->shouldRenderJsonWhen(fn (Request $request) => $request->is('api/*'));
    })->create();
```

`bootstrap/providers.php` is a plain list of provider class names (the application's own providers):

```php
return [ App\Providers\AppServiceProvider::class ];
```

Layout of the example: `app/`, `bootstrap/` (`app.php`, `providers.php`, `cache/`), `config/` (one PHP file per concern: `app`, `auth`, `cache`, `database`, `filesystems`, `logging`, `mail`, `queue`, `session`, `services`, `octane`, plus package configs), `routes/` (`web.php`, `console.php`), `public/`, `storage/`, `database/` (migrations, seeders, factories), `resources/`, `tests/`.

### HTTP request path (verified in source)

`Application::handleRequest()` does exactly three things: resolve the HTTP kernel from the container, `$kernel->handle($request)->send()`, then `$kernel->terminate($request, $response)`.

`Foundation/Http/Kernel::handle()`:

1. Record start time; enable HTTP method override.
2. `sendRequestThroughRouter()`: bind the request into the container as `request`, call `bootstrap()` if not yet bootstrapped, then run a `Pipeline` of the **global middleware** whose final destination is the router.
3. Any `Throwable` is reported through the `ExceptionHandler` contract and rendered into a response (`renderException`).
4. Dispatch `RequestHandled` event.

The **bootstrappers** run once per process, in this order (`Kernel::$bootstrappers`):

```
LoadEnvironmentVariables   (.env via phpdotenv)
LoadConfiguration          (cached config file, or every config/*.php merged with framework defaults)
HandleExceptions           (error/exception handlers)
RegisterFacades            (facade aliases + the static $app)
RegisterProviders          (Application::registerConfiguredProviders)
BootProviders              (Application::boot)
```

The router (`Routing/Router.php`): `dispatch` → `dispatchToRoute` → `findRoute` (fires `Routing` event, matches, binds the matched `Route` into the container) → `runRoute` (fires `RouteMatched`) → `runRouteWithinStack`, which is a **second** `Pipeline`: the route's middleware (group, aliased, controller-declared) wrapping `$route->run()`. The route action is invoked through the container (`call`), so controller methods get dependency injection by type-hint. The result passes through `prepareResponse` (converts arrays, `Responsable`, models/resources, strings into a `Response`).

Termination (`Kernel::terminate`): fire `Terminating`; call `terminate($request, $response)` on every middleware that defines it (global and route); run the application's `terminating` callbacks; fire any registered slow-request-duration handlers.

So the middleware model is a **onion built by `Pipeline::then()`** using `array_reduce` over closures. Each pipe is a callable or a class resolved from the container with signature `handle($passable, Closure $next)`. The pipeline can also wrap the whole thing in a DB transaction (`withinTransaction`) and has a `finally` hook (verified in `Pipeline/Pipeline.php`). `Illuminate\Contracts\Pipeline\Pipeline` is a generic interface (`send`, `through`, `via`, `then`) used outside HTTP too (the Bus uses it for command pipes).

### Console path (verified)

`Foundation/Console/Kernel::handle($input, $output)`: bootstrap (same six bootstrappers, except `env:encrypt` / `env:decrypt` which bootstrap without booting providers), then `getArtisan()->run($input, $output)`, which is a Symfony Console `Application` whose commands are loaded lazily through `ContainerCommandLoader`. Providers contribute commands with `$this->commands([...])` (a static `Artisan::starting` hook). `routes/console.php` holds closure commands and the task schedule. Terminate: `terminate($input, $status)`.

### Long-running runtimes

Octane (installed locally, `laravel/octane`) keeps the booted application in memory across requests (Swoole, RoadRunner, FrankenPhp servers). `config/octane.php` lists listeners that run per request/task/tick to "flush state between requests" (`FlushTemporaryContainerInstances`, `DisconnectFromDatabases`, `CollectGarbage`, `FlushOnce`, and so on). This is a direct admission that framework state is not request-scoped by default; see 1.2 on `scoped` bindings and Part 2 "Performance runtimes".

## 1.2 The service container

Files: `Container/Container.php` (1856 lines), `Contracts/Container/Container.php`, `BoundMethod.php`, `ContextualBindingBuilder.php`. `Foundation\Application` extends `Container` and implements `Contracts\Foundation\Application`.

### Contract (verified, trimmed)

```php
interface Container extends ContainerInterface {   // PSR-11
    public function get(string $id);
    public function bound($abstract);
    public function alias($abstract, $alias);
    public function tag($abstracts, $tags);
    public function tagged($tag);
    public function bind($abstract, $concrete = null, $shared = false);
    public function bindIf($abstract, $concrete = null, $shared = false);
    public function singleton($abstract, $concrete = null);
    public function scoped($abstract, $concrete = null);
    public function extend($abstract, Closure $closure);          // decorate after build
    public function instance($abstract, $instance);
    public function addContextualBinding($concrete, $abstract, $implementation);
    public function when($concrete);                              // contextual builder
    public function factory($abstract);
    public function make($abstract, array $parameters = []);
    public function call($callback, array $parameters = [], $defaultMethod = null);
    public function resolved($abstract);
    public function beforeResolving($abstract, ?Closure $callback = null);
    public function resolving($abstract, ?Closure $callback = null);
    public function afterResolving($abstract, ?Closure $callback = null);
}
```

### Binding kinds

| Kind | Meaning | Notes |
|---|---|---|
| `bind(abstract, concrete)` | New instance each `make` | `concrete` is a class name or a `Closure($container, $params)` |
| `singleton` | One instance per container (per worker process) | `bind(..., shared: true)` |
| `scoped` | One instance per "scope", flushed by `forgetScopedInstances()` | Exists because Octane/queue workers reuse the container; request scope is not automatic |
| `instance` | Pre-built object | |
| `bindIf`, `singletonIf`, `scopedIf` | Register only if absent | Used by packages so apps can override |
| Contextual | `when(Foo::class)->needs(Bar::class)->give(...)` | Different implementation per consumer; also `giveTagged`, `giveConfig` |
| Tagged | `tag([A::class, B::class], 'reports')`, `tagged('reports')` | Returns a lazy `RewindableGenerator` |
| `extend` | Wrap/decorate the resolved object | Applied after build, before caching as singleton |
| Alias | Many string names to one binding | `Application::registerCoreContainerAliases()` maps `'cache'`, `'cache.store'`, `Illuminate\Cache\CacheManager`, `Illuminate\Contracts\Cache\Factory` to one binding |

### Resolution algorithm (verified, `Container::resolve`)

1. Resolve alias; run `beforeResolving` callbacks.
2. Look for a **contextual** binding for the current build stack.
3. If a singleton instance exists and there are no override parameters or contextual binding, return it.
4. Find the concrete (registered binding, or the abstract name itself).
5. If the concrete is a closure or the same name as the abstract → `build($concrete)`; else `make($concrete)` recursively.
6. Apply `extend` decorators.
7. If shared, store the instance.
8. Fire `resolving` / `afterResolving` callbacks.

### Auto-wiring by reflection (verified, `Container::build`)

`build()` does `new ReflectionClass($concrete)`, refuses non-instantiable types (interfaces without a binding raise `BindingResolutionException`), reads the constructor's `ReflectionParameter`s, and for each parameter resolves by (a) a **PHP attribute** on the parameter, (b) the parameter's class type via `make`, (c) an explicit override parameter, (d) the default value. A build stack is kept for circular-dependency detection (`CircularDependencyException`).

Declarative resolution attributes exist in v13 (`Container/Attributes/`): `#[Config]`, `#[Cache]`, `#[Auth]`, `#[Authenticated]`, `#[CurrentUser]`, `#[DB]`, `#[Database]`, `#[Log]`, `#[Storage]`, `#[Context]`, `#[RouteParameter]`, `#[Tag]`, `#[Give]`, and class-level `#[Bind]`, `#[Singleton]`, `#[Scoped]`. A class can also implement `SelfBuilding` to construct itself.

`Container::call()` (via `BoundMethod`) performs the same reflection on any callable, so **method injection** works: controller actions, `ServiceProvider::boot()`, event listeners, queued job `handle()` methods, middleware constructors.

### What depends on PHP dynamism

Strings as service ids (`'cache'`, `'db'`, `'events'`), constructor reflection at runtime, closures as factories, `instance` of anything, `__call` forwarding. Everything is resolved at run time and discovered failures are run-time `BindingResolutionException`s. There is no compile-time wiring graph.

## 1.3 Service providers in detail

File: `Support/ServiceProvider.php` (662 lines). Registration machinery: `Foundation/Application.php`, `Foundation/ProviderRepository.php`, `Foundation/PackageManifest.php`, `Support/DefaultProviders.php`.

### The two phases and why

```php
abstract class ServiceProvider {
    protected $app;
    public function __construct($app) { $this->app = $app; }
    public function register() { }          // phase 1: only bind
    // public function boot() { }           // phase 2: optional, method-injected
}
```

**`register()`** runs for every provider, in order, before any `boot()`. Rule (stated in the docs, enforced by convention): `register()` must only bind things into the container and must not resolve other services, because other providers have not registered yet. **`boot()`** runs after *all* providers are registered, so it can safely resolve anything. `Application::boot()` fires `booting` callbacks, calls `bootProvider()` for each provider (`$this->app->call([$provider, 'boot'])`, so `boot()` gets **method injection**), sets `booted = true`, fires `booted` callbacks. A provider registered after the app has booted is booted immediately (`register()` checks `isBooted()`).

The reason for two phases is purely ordering: bindings must all exist before any provider uses them, without needing a dependency graph between providers. It substitutes for a topological sort.

Additional per-provider hooks: `booting(Closure)` / `booted(Closure)`; and conventional `bindings` and `singletons` array properties which `Application::register()` automatically registers (verified: `property_exists($provider, 'bindings')`).

### What a provider typically does (the helper methods, verified)

| Concern | Helper |
|---|---|
| Bind contracts/implementations | `$this->app->bind/singleton/scoped(...)` in `register()` |
| Merge package default config under the app's | `mergeConfigFrom($path, $key)` (skipped when config is cached) |
| Recursive merge variant | `replaceConfigRecursivelyFrom` |
| Load routes | `loadRoutesFrom($path)` (skipped when routes are cached) |
| Views / Blade components | `loadViewsFrom($path, $ns)`, `loadViewComponentsAs($prefix, [...])` |
| Translations | `loadTranslationsFrom`, `loadJsonTranslationsFrom` |
| Migrations / factories | `loadMigrationsFrom`, `loadFactoriesFrom` |
| Publishable assets (copy defaults into the app) | `publishes([$from => $to], $groups)`, `publishesMigrations` |
| Console commands | `$this->commands([...])` |
| Event listeners | `Event::listen` / `$this->app['events']->listen` in `boot()` |
| Post-resolve hooks | `callAfterResolving($name, $cb)` (runs now if already resolved, else when resolved) |
| Optimise/reload command registration | `optimizes()`, `reloads()` (static registries) |

Publishing keeps static registries on the base class (`static::$publishes`, `static::$publishGroups`), which is the global state `vendor:publish` reads.

### Deferred providers (verified, `ProviderRepository`)

A provider that implements `Contracts\Support\DeferrableProvider` declares `provides(): array` of service ids (and optionally `when(): array` of events). `ProviderRepository::load()` compiles a **manifest** (`bootstrap/cache/services.php`) with three lists: `eager` providers, `deferred` (service id → provider), and `when` (event listeners that trigger registration). Eager providers are registered immediately. For deferred ones, `Application::make()` / `resolve()` call `loadDeferredProviderIfNeeded($abstract)`; if the service id is in the deferred map and no instance exists, the provider is registered (and booted) right then. The manifest is recompiled when the provider list changes. This is lazy loading by service id, a startup-cost optimisation for a runtime where every request re-boots.

### Provider ordering (verified, `registerConfiguredProviders`)

The configured list is partitioned into framework providers (class name starts with `Illuminate\`) and others; the **package-discovered providers are spliced in after the framework group**:

```
1. Framework providers (Illuminate\...)        from Support/DefaultProviders.php
2. Package-discovered providers                from PackageManifest
3. Application providers                       from bootstrap/providers.php
```

Before this, `Application::registerBaseServiceProviders()` hard-registers `EventServiceProvider`, `LogServiceProvider`, `ContextServiceProvider`, `RoutingServiceProvider`. Boot order equals registration order. There is no explicit dependency declaration between providers; later providers can override earlier bindings (last bind wins; `bindIf` avoids this).

### Package auto-discovery (verified, `PackageManifest`)

`composer.json` of a package carries `extra.laravel.providers` and `extra.laravel.aliases`. `PackageManifest::build()` reads `vendor/composer/installed.json`, collects those keys per package, honours `dont-discover` in the root `composer.json`, and writes `bootstrap/cache/packages.php`. Installing a package therefore wires its provider with no app change. It depends entirely on Composer metadata plus string class names.

### Example 1: `CacheServiceProvider` (verified)

A deferred provider. `register()` binds, `provides()` lists the ids:

```php
class CacheServiceProvider extends ServiceProvider implements DeferrableProvider {
    public function register() {
        $this->app->singleton('cache',        fn ($app) => new CacheManager($app));
        $this->app->singleton('cache.store',  fn ($app) => $app['cache']->driver());
        $this->app->singleton('cache.psr6',   fn ($app) => new Psr16Adapter($app['cache.store']));
        $this->app->singleton('memcached.connector', fn () => new MemcachedConnector);
        $this->app->singleton(RateLimiter::class, fn ($app) =>
            new RateLimiter($app->make('cache')->driver($app['config']->get('cache.limiter'))));
    }
    public function provides() {
        return ['cache', 'cache.store', 'cache.psr6', 'memcached.connector', RateLimiter::class];
    }
}
```

No `boot()`. The Manager is bound as the root; `cache.store` is the **default store**, resolved through the manager. The `RateLimiter` is built on a cache store, so rate limiting is a consumer of the cache subsystem, not a separate one.

### Example 2: `FilesystemServiceProvider` (verified)

Eager (not deferrable), has both phases:

- `register()`: `files` (a plain local `Filesystem` helper), `filesystem` (`FilesystemManager`), `filesystem.disk` (default disk), `filesystem.cloud` (default cloud disk).
- `boot()`: `serveFiles()`. For every disk in `config('filesystems.disks')` with `driver: local` and `serve: true`, it registers (inside an `app->booted(...)` callback) two **routes**: `GET {url-path}/{path}` named `storage.{disk}` and `PUT` for uploads (signed temporary URLs for local disks). It throws if two served disks share a URL path. So a "storage" provider also contributes HTTP routes derived from config. This shows a provider contributing to three subsystems (container, config reading, router).

## 1.4 The Manager / driver pattern

This is the pattern the owner most wants to reuse, so the exact mechanics matter, including the fact that Laravel itself is not consistent about it.

### 1.4.1 The base `Support\Manager` (verified, 200 lines)

```php
abstract class Manager {
    protected $container;  protected $config;
    protected $customCreators = [];   // name => Closure
    protected $drivers = [];          // name => created instance (cache)

    abstract public function getDefaultDriver();

    public function driver($driver = null) {
        $driver = enum_value($driver) ?: $this->getDefaultDriver();
        if (is_null($driver)) throw new InvalidArgumentException(...);
        return $this->drivers[$driver] ??= $this->createDriver($driver);   // create once, memoise
    }
    protected function createDriver($driver) {
        if (isset($this->customCreators[$driver])) return $this->callCustomCreator($driver);
        $method = 'create'.Str::studly($driver).'Driver';
        if (method_exists($this, $method)) return $this->$method();        // convention by method name
        throw new InvalidArgumentException("Driver [$driver] not supported.");
    }
    protected function callCustomCreator($driver) { return $this->customCreators[$driver]($this->container); }
    public function extend($driver, Closure $callback) { /* bind $this into closure, store */ return $this; }
    public function getDrivers();  public function forgetDrivers();
    public function __call($method, $parameters) { return $this->driver()->$method(...$parameters); } // proxy to default
}
```

Mechanics, step by step:

1. **Default selection**: `getDefaultDriver()` is abstract; each subclass reads a config key (for example `config('session.driver')`, `config('hashing.driver')`).
2. **Creation**: by **method-name convention** `create<StudlyName>Driver()` found with `method_exists`, or by a **custom creator** registered with `extend()`. Custom creators win.
3. **Caching**: created instances are memoised in `$drivers`; `forgetDrivers()` clears.
4. **Proxy**: `__call` forwards any unknown method to the default driver, which is what makes `Cache::get()` work on a manager.
5. **Extension**: `extend('name', fn ($container) => new MyDriver(...))`; the closure is rebound so `$this` is the manager (`RebindsCallbacksToSelf`).

Subclasses of this base in v13 (verified by grep): `HashManager`, `SessionManager`, `MaintenanceModeManager`, `Notifications\ChannelManager`. `Concurrency\ConcurrencyManager` extends `MultipleInstanceManager` (a variant keyed by named instances).

### 1.4.2 The "named store/connection over a driver type" variant (the one that matters)

The managers people actually use (`CacheManager`, `FilesystemManager`, `MailManager`, `QueueManager`, `DatabaseManager`, `BroadcastManager`, `AuthManager`, `LogManager`, `RedisManager`, `PasswordBrokerManager`) **do not extend `Support\Manager`**. They are separate classes with the same shape, and they separate two ideas the base class conflates:

- a **named instance** (store / disk / mailer / connection / guard / channel), selected by name, with a configured **default name**;
- a **driver type** (`redis`, `s3`, `smtp`, `sqs`), a key inside that named instance's config.

Config shape (verified from the example `config/cache.php`, `mail.php`, `queue.php`, `filesystems.php`):

```php
// config/cache.php
'default' => env('CACHE_STORE', 'database'),
'stores' => [
    'redis'    => ['driver' => 'redis', 'connection' => 'cache', 'lock_connection' => 'default'],
    'database' => ['driver' => 'database', 'table' => 'cache', 'lock_table' => ...],
    'failover' => ['driver' => 'failover', 'stores' => ['database', 'array']],
],
```

Lookup flow in `CacheManager` (verified):

```php
public function store($name = null) {
    $name = enum_value($name) ?? $this->getDefaultDriver();   // default name from config
    return $this->stores[$name] ??= $this->resolve($name);    // memoise per name
}
public function resolve($name) {
    $config = $this->getConfig($name);                        // config("cache.stores.$name")
    if (is_null($config)) throw new InvalidArgumentException("Cache store [{$name}] is not defined.");
    return $this->build(Arr::add($config, 'store', $name));
}
public function build(array $config) {                        // also usable on-demand with an ad-hoc config
    if (isset($this->customCreators[$config['driver']])) return $this->callCustomCreator($config);
    $m = 'create'.ucfirst($config['driver']).'Driver';
    if (method_exists($this, $m)) return $this->$m($config);
    throw new InvalidArgumentException("Driver [{$config['driver']}] is not supported.");
}
// extension: $customCreators[$driver] = $callback; creator receives ($app, $config)
```

Per-manager differences worth knowing (all verified):

| Manager | Name key | Type key | Extension API | Custom creator receives |
|---|---|---|---|---|
| `CacheManager` | store | `driver` | `extend($driver, Closure)` | `($app, $config)` |
| `FilesystemManager` | disk | `driver` | `extend` | `($app, $config)` |
| `MailManager` | mailer | `transport` | `extend` (creator returns a Symfony transport) | `($config)` only |
| `QueueManager` | connection | `driver` | `extend` / `addConnector($driver, Closure)` returning a **Connector**; `connect($config)` builds the queue | none (connector built, then `connect($config)`) |
| `DatabaseManager` | connection | `driver` | `extend($name, callable)`; built by `ConnectionFactory` + connectors per driver | |
| `BroadcastManager` | connection | `driver` | `extend` | `($app, $config)` |
| `AuthManager` | guard | `driver` | `extend` (guards), `provider()` (user providers), `viaRequest()` | `($app, $name, $config)` |
| `LogManager` | channel | `driver` | `extend` | `($app, $config)` |
| `Notifications\ChannelManager` | channel | n/a | `extend`; also resolves any **class name** as a channel | |

Further observations:

- **Named instances compose other named instances.** A `failover` cache store lists other stores by name; `failover`/`roundrobin` mailers list other mailers; the `failover` queue connection lists other connections; `scoped` filesystem disks wrap another disk with a prefix (`FilesystemManager::createScopedDriver`); a cache `storage` store sits on a filesystem disk; `database` cache and queue name a DB connection. Composition by name through config is the pattern's strongest feature and is a runtime graph, not typed.
- **Return type** is the contract (`Cache\Repository`, `Filesystem`, `Mailer`, `Queue`, `Guard`), with the real behaviour sitting in a `Store`/`Adapter` behind it. For cache, driver authors implement the lower-level `Store` contract and the manager wraps it in `Repository` (`$this->repository($store, $config)`), which adds the user-facing API (`remember`, `pull`, events, tags, locks).
- **Facade-style proxy**: `__call` on the manager forwards to the default instance.
- **Purge / forget**: `purge($name)`, `forgetDriver($name)`, `forgetDisk`, `forgetMailers`, `forgetGuards` drop memoised instances (used by Octane and tests).
- **Configuration merge**: `LoadConfiguration::mergeableOptions` merges framework default `stores`, `disks`, `mailers`, `connections`, `channels`, `guards`, `providers` with the app's file, so a package can contribute a store name by default.
- **Default naming** is an environment variable per concern: `CACHE_STORE`, `MAIL_MAILER`, `QUEUE_CONNECTION`, `FILESYSTEM_DISK`, `DB_CONNECTION`.
- **Dispatch is by string and by method name** (`'create'.ucfirst($driver).'Driver'` with `method_exists`). That is the biggest dynamism dependency; a typed framework replaces it with an explicit registry keyed by driver name (see 1.4.3).

### 1.4.3 Transfer note for the pattern

Keep: named instances + driver type + default name, instances memoised per name, composition by name (failover, scoped, storage-on-disk), `extend` as the single extension point, config as plain data. Change: replace `method_exists` convention with a typed registry `Map<DriverName, DriverFactory<Config, Instance>>` populated by modules; give each driver a **typed config object** deserialised and validated at startup rather than a free-form array read lazily; fail at boot (not first use) when a configured name references an unknown driver or unknown composed instance (Laravel fails lazily at first resolution). Drop: the `__call` proxy to the default instance, and the base-class vs bespoke-manager inconsistency (one generic `Manager<Contract, Config>` is enough).

## 1.5 Contracts, facades and helpers

### Contracts (`Illuminate\Contracts`, verified: 170-odd files)

A separate package of **interfaces and exceptions only**: `Cache\Repository`, `Cache\Store`, `Cache\Lock`, `Filesystem\Filesystem`, `Mail\Mailer`, `Queue\Queue`, `Events\Dispatcher`, `Auth\Guard`, `Auth\Access\Gate`, `Encryption\Encrypter`, `Hashing\Hasher`, `Config\Repository`, and so on. Marker contracts (zero methods) express behaviour: `ShouldQueue`, `ShouldBeUnique`, `ShouldBeEncrypted`, `ShouldBroadcast`, `ShouldDispatchAfterCommit`, `Isolatable`, `DeferrableProvider`, `SelfBuilding`.

**This is plain interface-based design and is the most portable part of Laravel.** Two caveats observed in source:

1. Method signatures in `Contracts` are **mostly untyped** with types only in docblocks (`public function get($key);`, `public function put($key, $value, $seconds);`). A typed framework must supply real parameter and return types, and several return types are mixed (`Cache::get` returns anything).
2. Several contracts are only half the API. `Cache\Lock` has no `refresh()`; the abstract `Cache\Lock` class adds it and the contract does not (verified). The container is also asked for concrete classes (`CacheManager`) as often as for contracts.

### Facades (`Support/Facades/*`, verified)

A facade is a class with a static `__callStatic` that resolves a service from the container and forwards the call:

```php
abstract class Facade {
    protected static $app;
    protected static $resolvedInstance;
    protected static function getFacadeAccessor() { throw ...; }      // e.g. 'cache', 'mail.manager'
    public static function getFacadeRoot() { return static::resolveFacadeInstance(static::getFacadeAccessor()); }
    public static function __callStatic($method, $args) { $instance = static::getFacadeRoot(); return $instance->$method(...$args); }
    public static function swap($instance) { static::$resolvedInstance[$name] = $instance; static::$app->instance($name, $instance); }
    public static function shouldReceive() / spy() / partialMock()   // Mockery
}
```

The facade does not implement the contract; IDE support comes from `@method` docblocks. It is **runtime indirection plus global static state** (`static::$app`, `$resolvedInstance`). Its job is ergonomics (`Cache::get(...)` anywhere) and test substitution via `swap`.

**Real-time facades** (docs; not source-verified): prefixing a class import with `Facades\` (for example `use Facades\App\Services\Publisher;`) auto-generates a facade for any class at run time.

### Helpers (`Support/helpers.php`, `Foundation/helpers.php`)

Global functions: `app()`, `config()`, `cache()`, `auth()`, `event()`, `dispatch()`, `now()`, `logger()`, `session()`, `route()`, `view()`, `abort()`, `tap()`, `retry()`, `value()`, `env()`. Each is `app()->make(...)`. Same category as facades: service location via a global.

### Which is which

| Mechanism | Nature |
|---|---|
| Contracts | Plain interface design. Keep. |
| Constructor type-hint injection | Plain DI, but wired by runtime reflection. Keep the idea, wire at compile time. |
| Facades | Service locator + `__callStatic`. Drop. |
| Helpers (`app()`, `config()`) | Service locator via global. Drop. |
| Real-time facades | Runtime code generation. Drop. |
| String ids (`'cache'`) | Untyped locator keys. Drop. |

## 1.6 Configuration

Verified in `Foundation/Bootstrap/LoadConfiguration.php`, `LoadEnvironmentVariables.php`, `Contracts/Config/Repository.php`.

- **Files.** `config/*.php`, each `return [...]`, key = file name, loaded into `Config\Repository` (dot-notation `get('cache.stores.redis.connection')`, `set`, `push`, `prepend`). Config is code, so it may call `env()`, `storage_path()`, `Str::slug(...)`.
- **Framework defaults.** The framework ships base config arrays; `loadConfigurationFile` merges them under the app's file, with array-valued `mergeableOptions` (`stores`, `disks`, `mailers`, `connections`, `channels`, `guards`, `providers`, `passwords`) merged one level deeper.
- **Environment.** `.env` parsed by phpdotenv into the process env; `env('KEY', default)` is called only inside config files. Environment name (`APP_ENV`) comes from `app.env`. `.env.{environment}` files are supported via `APP_ENV`/`--env` (`checkForSpecificEnvironmentFile`).
- **Caching.** `php artisan config:cache` writes one PHP file (`bootstrap/cache/config.php`); when present `LoadConfiguration` loads it and skips every file and every `env()` call. Consequence: `env()` outside config files returns null after caching (documented gotcha).
- **Merge by packages.** `mergeConfigFrom` in providers; skipped when config is cached.
- **Encrypted env files.** `env:encrypt` / `env:decrypt` (`Foundation/Console/EnvironmentEncryptCommand.php`, verified) encrypt the whole `.env` file with the app's encrypter into `.env.{env}.encrypted`, which can be committed and decrypted at deploy time with a key. This is the only secret-handling feature in core.
- **Per-environment.** `$app->environment('local', 'production')`, `.env` per environment, config values chosen by `env`. No profile/overlay mechanism beyond env vars and env files.

**Not provided (verified by grep of the whole `Illuminate` tree for `ssm`, `secretsmanager`, `parameter store`, `vault`, `tenant`, `quota`, `whatsapp`, `vonage`, `twilio`, `UnitOfWork`, `outbox`):**

- No remote or database-backed configuration source. `Config\Repository` is an in-memory array filled at bootstrap from local files.
- No secret-store integration: no AWS Parameter Store, Secrets Manager, Vault, Azure Key Vault, GCP Secret Manager. (The one `ssm` grep hit set is incidental text.) The AWS-based drivers (S3, SQS, SES, DynamoDB) take credentials from config/env values or the AWS SDK default provider chain; the framework does not resolve secrets.
- No typed or schema-validated config; a missing key yields `null` or the default.
- No runtime reload; config is read once per process (Octane workers keep it for their lifetime).

## 1.7 Testing support

### The mechanism: swap the binding

All fakes work by **replacing the service in the container (and in the facade's static cache) with an object implementing the same contract that records calls**. Verified class declarations in `Support/Testing/Fakes/`:

```php
class EventFake        implements Dispatcher, Fake
class MailFake         implements Factory, Fake, Mailer, MailQueue
class BusFake          implements Fake, QueueingDispatcher
class NotificationFake implements Fake, NotificationDispatcher, NotificationFactory
class QueueFake        extends QueueManager implements Fake, Queue
class ExceptionHandlerFake implements ExceptionHandler, Fake
```

`Fake` is an empty marker interface so `Facade::isFake()` can test `instanceof Fake`. Entry point per facade (verified):

```php
// Mail facade
public static function fake() {
    $actual = static::isFake() ? static::getFacadeRoot()->manager : static::getFacadeRoot();
    return tap(new MailFake($actual), fn ($fake) => static::swap($fake));   // swap = static cache + app->instance
}
```

The fake takes the **real manager as a constructor argument** (so it can still delegate for unfaked behaviour) and exposes assertions: `assertSent(Mailable::class, fn ($mail) => $mail->hasTo(...))`, `assertQueued`, `assertNothingSent`, `assertSentTimes`. `Event::fake()` also re-points the Eloquent model event dispatcher (`Model::setEventDispatcher($fake)`) and refreshes the cache's dispatcher, showing how many places hold a reference to the dispatcher.

| Fake | How |
|---|---|
| `Mail::fake()`, `Notification::fake()`, `Queue::fake()`, `Bus::fake()`, `Event::fake()` | Recording fake implementing the contract; `assert*` methods with closure truth-tests |
| `Storage::fake('disk')` | **Not a recording fake**: replaces the disk with a real `local` driver rooted in `storage/framework/testing/disks/{disk}` (cleaned first, parallel-test token appended) and stubs temporary-URL builders. Assertions are `Storage::disk()->assertExists(...)` |
| `Http::fake([...])` | `Http\Client\Factory::fake($callback)` stub map by URL pattern, `fakeSequence`, `preventStrayRequests()`, `assertSent`, `recorded()` |
| `Process::fake()` | Same shape, `preventStrayProcesses()`, `assertRan` |
| Time travel | `Foundation/Testing/Concerns/InteractsWithTime` + `Wormhole`: `travel(5)->days()`, `freezeTime()`; implemented by replacing the `Carbon` "now" |
| Auth | `actingAs($user, $guard)` on the test case |
| Exceptions | `withoutExceptionHandling()`, `Exceptions::fake()` |
| Middleware | `withoutMiddleware()` sets `middleware.disable` |
| Mocks | `$this->mock(Foo::class, ...)` / `instance()` bind a Mockery mock into the container; `Facade::shouldReceive()` |

### Database testing traits (verified in `Foundation/Testing/`)

`RefreshDatabase` (migrate once, wrap each test in a transaction), `LazilyRefreshDatabase`, `DatabaseTransactions`, `DatabaseMigrations` (migrate each test), `DatabaseTruncation`. Plus `InteractsWithDatabase` assertions (`assertDatabaseHas`, `assertDatabaseMissing`, `assertSoftDeleted`). Model **factories** (`Eloquent/Factories/Factory.php`) build and persist test data. Parallel testing is supported with per-process database suffixes.

### HTTP testing

`MakesHttpRequests` runs a request through the real kernel in-process (`$this->get('/x')`, `postJson`) and returns a `TestResponse` with fluent assertions. There is no network.

### Observations for a typed framework

The fake-as-contract-implementation idea is the valuable part and is just ordinary design: each capability's contract is the seam, and the framework ships an in-memory "recording" implementation of it. The facade `swap` and Mockery integration are the PHP-specific parts. See Part 2 for which capabilities have a recording fake and which do not (storage and database use real local implementations).

---

# Part 2. Capability inventory

Notation: **C** = contract, **D** = drivers. Signatures are copied from `Illuminate/Contracts/**` (v13.17.0) and are mostly untyped in the source (docblock types only). Where a first-party package is not installed locally, the section says so.

---

## 2.1 Routing and middleware

**(a) What it gives the developer.** URL-to-handler mapping with verbs, named routes, groups (prefix, middleware, domain), route-model binding, resource/API-resource conventions, route caching, URL generation including signed URLs, a fallback route, rate limiting hooks, and a composable middleware pipeline. Routes are written in PHP files (`routes/web.php`) or declared on controllers with attributes.

**(b) Contract.**

```php
interface Registrar {     // Contracts/Routing/Registrar
    public function get($uri, $action);   public function post($uri, $action);
    public function put($uri, $action);   public function delete($uri, $action);
    public function patch($uri, $action); public function options($uri, $action);
    public function match($methods, $uri, $action);
    public function resource($name, $controller, array $options = []);
    public function group(array $attributes, $routes);
    public function substituteBindings($route);
}
interface UrlRoutable {   // route-model binding contract
    public function getRouteKey();   public function getRouteKeyName();
    public function resolveRouteBinding($value, $field = null);
}
interface UrlGenerator {
    public function route($name, $parameters = [], $absolute = true);
    public function signedRoute($name, $parameters = [], $expiration = null, $absolute = true);
    public function temporarySignedRoute($name, $expiration, $parameters = [], $absolute = true);
}
// Middleware has no interface: a class with  handle($request, Closure $next)  (convention)
// optional  terminate($request, $response)
```

**(c) Drivers/extension.** No drivers. Extension points: global middleware list, middleware groups and aliases (`$middleware->alias([...])`), `Route::bind` / `Route::model` explicit binders, `Route::pattern`, `Route::macro`. Route cache is a compiled array (`CompiledRouteCollection`).

**(d) Configuration.** Mostly code: `->withRouting(web:, api:, commands:, channels:, health:)` in `bootstrap/app.php`; middleware configured in `->withMiddleware(function (Middleware $m) {...})`. Controller attributes added in 13: `#[Middleware('auth')]`, `#[Authorize('create', [Comment::class,'post'])]` (verified: `Routing/Attributes/Controllers/`).

**(e) Layered features.** Resource and API resource routes (`Route::apiResource`), singleton resources, implicit binding by type-hint (a `Post $post` parameter is resolved from `{post}`), scoped bindings, route groups with domain/prefix/name, `ThrottleRequests` middleware (named limiters), `ValidateSignature`, `SubstituteBindings`, `HandleCors`, `TrustProxies`, `PreventRequestForgery` (renamed/formalised CSRF in 13), `SetCacheHeaders`, health endpoint, maintenance mode (`MaintenanceModeManager` with file/cache/array drivers), `Route::redirect/view`.

**(f) PHP dynamism.** Controllers referenced by class name string or `[Class::class, 'method']`; invocation through reflection (`RouteDependencyResolverTrait`, `ResolvesRouteDependencies`) to inject parameters by type; middleware referenced by alias string with `name:param1,param2` argument parsing (`Pipeline::parsePipeString` splits on `:` and `,`, giving string-typed parameters); implicit model binding calls `resolveRouteBinding` on a model class discovered by reflection; global static `Route` facade; closure routes cannot be cached unless serialisable (route cache requires controller-based routes, per docs).

**(g) Transfer note.** Keep: a pipeline abstraction with `handle(request, next)`, middleware groups and aliases, named routes + URL generator, signed URLs, route groups, resource conventions. Change: routes registered in code or by annotation/macros that run at startup and produce a typed route table (compile-time in Rust/Java annotation processing; build-time in TS), middleware parameters typed (a `RateLimit(perMinute=60)` value, not `"throttle:60,1"`), model binding as an explicit typed `Resolver<Id, T>` that the domain layer supplies rather than a method on an active-record model. Drop: string middleware arguments, facades, route closures as the default style, binding via model reflection.

---

## 2.2 Requests, validation and form requests

**(a)** `Request` object wrapping Symfony HttpFoundation, with input access, files, headers, content negotiation (`expectsJson`), user and route access. Validation by rule strings or rule objects, run explicitly (`$request->validate([...])`) or automatically by typing a controller parameter as a **FormRequest** (a class with `rules()` and `authorize()`), which validates and authorises before the controller body runs and returns 422 JSON or a redirect-with-errors.

**(b) Contract.**

```php
interface Validator extends MessageProvider {            // Contracts/Validation/Validator
    public function validate();  public function validated();
    public function fails();     public function failed();
    public function sometimes($attribute, $rules, callable $callback);
    public function after($callback);  public function errors();
}
interface Factory {                                      // Contracts/Validation/Factory
    public function make(array $data, array $rules, array $messages = [], array $attributes = []);
    public function extend($rule, $extension, $message = null);
}
interface ValidationRule {                               // object rule, v10+
    public function validate(string $attribute, mixed $value, Closure $fail): void;
}
interface ValidatesWhenResolved { public function validateResolved(); }  // FormRequest hook
```

**(c) Extension.** Custom rule objects implementing `ValidationRule`, closure rules, `Validator::extend('name', fn)`. Rules shipped as classes in `Validation/Rules/` (verified: `Email`, `Password`, `Unique`, `Exists`, `Enum`, `File`, `ImageFile`, `Dimensions`, `In`, `RequiredIf`, `AnyOf`, `Can`, …). No drivers.

**(d) Config.** None meaningful; rules are code. Messages come from translation files (`lang/*/validation.php`). A `Password` rule is configured fluently (`Password::min(8)->uncompromised()`), the "uncompromised" check calls an external HTTP service through `UncompromisedVerifier`.

**(e) Layered.** Rule strings with parameters (`'email' => 'required|email|max:255|unique:users'`), nested/wildcard keys (`users.*.email`), conditional rules, after-hooks, stop-on-first-failure, validated-data subset (`validated()`, `safe()`), `ValidationException` rendering to 422, Precognition (2.32) which runs a form request without the controller.

**(f) PHP dynamism.** The default rule language is **strings parsed at runtime** (`'required|max:255'`) dispatched to `validate<Name>` methods via `method_exists`/reflection-like lookup; data is an untyped associative array; `unique:users,email` embeds table and column names in a string; `FormRequest` is resolved by container type-hint and self-validates as a side effect of resolution (`ValidatesWhenResolved`).

**(g) Transfer.** Keep: a form-request concept where an input DTO type owns its validation and authorisation, validation errors as a structured 422 problem, rule objects. Change: validation attached to a typed input record (annotation/derive/schema) producing a typed value, not an array; rules are typed constructors (`Max(255)`, `Email`), not strings; cross-field and async (database uniqueness) rules should be explicit domain checks returning errors. Drop: the pipe-string DSL, array-based `validated()`, validation as a side effect of dependency resolution.

---

## 2.3 Responses and API resources

**(a)** Any value returned from a controller is turned into a response: strings, arrays (JSON), models, `Responsable` objects, `JsonResource` classes that shape a model/array into JSON (conditional attributes, relationship inclusion, pagination envelope). v13 adds first-party **JSON:API resources**.

**(b) Contract.**

```php
interface Responsable { public function toResponse($request); }          // Contracts/Support/Responsable
interface Arrayable   { public function toArray(); }
interface Jsonable    { public function toJson($options = 0); }
interface ResponseFactory { /* make, view, json, download, stream, streamDownload, file, redirectTo... */ }
```

**(c) Extension.** `Response::macro`, custom `Responsable`, `JsonResource` subclasses. No drivers. JSON:API lives in `Http/Resources/JsonApi/` (verified present, `JsonApiResource`, `RelationResolver`).

**(d) Config.** None.

**(e) Layered.** `JsonResource` / `ResourceCollection` with `whenLoaded`, `when`, `mergeWhen` (`ConditionallyLoadsAttributes`), pagination metadata (`Contracts/Pagination/*`: `Paginator`, `LengthAwarePaginator`, `CursorPaginator`), streamed and SSE responses (`StreamedEvent`), file downloads, cookies, conditional (ETag) responses (`CheckResponseForModifications`), redirects with flash data.

**(f) Dynamism.** Return-type polymorphism handled in `Router::prepareResponse` by `instanceof` chains; resources rely on magic `__get` delegation to the wrapped model (`DelegatesToResource`) and on `MissingValue` sentinels; relationships are included only if they are already loaded, which depends on Eloquent lazy-loading state.

**(g) Transfer.** Keep: explicit presentation mappers (resource classes) separate from the domain model, conditional inclusion, pagination envelopes, a response-conversion hook (`Responsable`). Change: resources map from a domain read model to a typed DTO; the HTTP layer returns typed results (`Result<T, Problem>`); content negotiation and problem details standardised. Drop: wrapping and magic-delegating an ORM entity, "loaded relationship" conditionals.

---

## 2.4 Authentication (guards, user providers)

**(a)** Establish who the caller is. A **guard** decides how identity is read from a request (session cookie, bearer token, or custom callback); a **user provider** decides how user records are retrieved and credentials validated (Eloquent or raw database). Includes login/logout, remember-me, password reset brokers, email verification, HTTP basic, "confirm password" gate, and login events.

**(b) Contract (verified).**

```php
interface Guard {
    public function check(); public function guest(); public function user(); public function id();
    public function validate(array $credentials = []); public function hasUser();
    public function setUser(Authenticatable $user);
}
interface StatefulGuard extends Guard {
    public function attempt(array $credentials = [], $remember = false);
    public function once(array $credentials = []);
    public function login(Authenticatable $user, $remember = false);
    public function loginUsingId($id, $remember = false);
    public function logout();
}
interface UserProvider {
    public function retrieveById($identifier);
    public function retrieveByToken($identifier, $token);
    public function updateRememberToken(Authenticatable $user, $token);
    public function retrieveByCredentials(array $credentials);
    public function validateCredentials(Authenticatable $user, array $credentials);
    public function rehashPasswordIfRequired(Authenticatable $user, array $credentials, bool $force = false);
}
interface Authenticatable {
    public function getAuthIdentifierName(); public function getAuthIdentifier();
    public function getAuthPasswordName();   public function getAuthPassword();
    public function getRememberToken(); public function setRememberToken($value);
}
interface Factory { public function guard($name = null); public function shouldUse($name); }
```

**(c) Drivers.** Guards: `session` (`SessionGuard`), `token` (`TokenGuard`), `RequestGuard` via `Auth::viaRequest('name', fn (Request $r) => ...)`. Providers: `eloquent`, `database`. Custom: `Auth::extend('jwt', fn ($app, $name, $config) => new JwtGuard(...))`, `Auth::provider('mongo', fn ($app, $config) => new MongoProvider(...))` (verified in `AuthManager`).

**(d) Config (example app, verified).**

```php
'defaults'  => ['guard' => 'web', 'passwords' => 'users'],
'guards'    => ['web' => ['driver' => 'session', 'provider' => 'users']],
'providers' => ['users' => ['driver' => 'eloquent', 'model' => App\Models\User::class]],
```

**(e) Layered.** Middleware `auth`, `auth.basic`, `verified`, `password.confirm`; events (`Attempting`, `Login`, `Failed`, `Lockout`, `Logout`, `PasswordReset`, ...); `AuthenticateSession` (invalidate other sessions on password change); login throttling (Fortify / starter kits via `RateLimiter`); `Auth::viaRequest`; first-party packages in 2.31.

**(f) Dynamism.** `Authenticatable` is almost always an Eloquent model, so identity is an active-record row; credentials are passed as an untyped `array`; guards are looked up by string name from config; `auth()->user()` and `Auth::user()` read from global state; `#[CurrentUser]` / `#[Authenticated]` container attributes exist in v13.

**(g) Transfer.** Keep: separating **guard** (how the request proves identity) from **user provider** (where principals live), a named-guard registry, per-route guard selection, events on login/logout/failure. Change: the principal is a typed `Principal` value (id, tenant, roles, claims) rather than an ORM model; credentials are typed (`PasswordCredentials`, `BearerToken`); the user provider is an interface the domain supplies, and it can be backed by an external identity platform (see Part 3 gaps). Drop: `Authenticatable` as a mandatory model interface, remember-token column conventions, `array $credentials`.

---

## 2.5 Authorisation (gates, policies)

**(a)** Decide whether a principal may perform an action on a resource. **Gates** are named closures or class methods; **policies** are classes bound to a model class, one method per ability (`view`, `update`, `delete`, ...). Used from controllers (`$this->authorize`), middleware (`can:update,post`), Blade (`@can`), and models (`$user->can(...)`).

**(b) Contract (verified).**

```php
interface Gate {
    public function has($ability);
    public function define($ability, $callback);
    public function resource($name, $class, ?array $abilities = null);
    public function policy($class, $policy);
    public function before(callable $callback);
    public function after(callable $callback);
    public function allows($ability, $arguments = []);   public function denies($ability, $arguments = []);
    public function check($abilities, $arguments = []);  public function any($abilities, $arguments = []);
    public function authorize($ability, $arguments = []); // throws AuthorizationException
    public function inspect($ability, $arguments = []);   // returns a Response with message/code
    public function forUser($user);
    public function getPolicyFor($class);
}
interface Authorizable { public function can($abilities, $arguments = []); }
```

**(c) Extension.** `Gate::define`, `Gate::policy(Model::class, ModelPolicy::class)`, `Gate::before` super-admin hook, `Gate::guessPolicyNamesUsing`. No drivers.

**(d) Config.** None; registration in a provider's `boot()`.

**(e) Layered.** Policy auto-discovery by naming convention, `Response::allow/deny` with messages and HTTP status, guest-user support (nullable user type-hints), `Gate::inspect`, `Gate::allowIf/denyIf`, `Can` validation rule, `#[Authorize]` controller attribute (13).

**(f) Dynamism.** **Policy discovery by string manipulation** (verified `Gate::guessPolicyName`): it derives `App\Policies\PostPolicy` from `App\Models\Post` by namespace rewriting then `class_exists`. Abilities are strings. Arguments are `mixed`. The user type is detected by reflection of the callback's first parameter (nullable means guests allowed).

**(g) Transfer.** Keep: policy-per-resource-type with before/after hooks and an `inspect` that returns a reason; ability checks as a first-class domain concern. Change: abilities as typed values/enums; policy registered explicitly against a type (`Policy<Order>`), no name-guessing; the check takes `(Principal, Action, Resource)`; return a decision value with reason. Drop: string ability names, namespace-derived discovery.

---

## 2.6 Sessions

**(a)** Server-side per-visitor state keyed by a cookie, with flash data, CSRF token, previous-URL tracking, ID regeneration, and optional encryption.

**(b) Contract (verified, abridged).**

```php
interface Session {
    public function getId(); public function setId($id);
    public function start();  public function save();
    public function all(); public function exists($key); public function has($key);
    public function get($key, $default = null); public function pull($key, $default = null);
    public function put($key, $value = null); public function flash(string $key, $value = true);
    public function token(); public function regenerateToken();
    public function forget($keys); public function flush(); public function invalidate();
    public function regenerate($destroy = false); public function migrate($destroy = false);
    public function getHandler();
}
```

**(c) Drivers (verified in `SessionManager`).** `file`, `cookie`, `database`, `memcached`, `redis`, `dynamodb`, `array`, `apc`, `null`, `native`. `SessionManager extends Support\Manager`, so extension is `Session::extend('name', fn ($app) => new CustomHandler)`; a custom driver is a PHP `SessionHandlerInterface`. Cache-backed drivers create a handler on a cache store.

**(d) Config.**

```php
'driver' => env('SESSION_DRIVER', 'database'),
'lifetime' => 120, 'encrypt' => false, 'cookie' => ..., 'same_site' => 'lax', 'store' => ..., 'table' => 'sessions',
```

**(e) Layered.** `StartSession` middleware, `AuthenticateSession`, flash messages, old-input, session blocking via cache locks (`->block()` on routes), `EncryptedStore`, cookie `SameSite`/`Secure` settings.

**(f) Dynamism.** `session()` helper, `Session` facade, `$request->session()`; data is an untyped bag. PHP's request-per-process model makes the `Store` a mutable global for the request.

**(g) Transfer.** Keep only if the framework serves browser sessions: a typed `SessionStore` interface with a handler driver, regenerate-on-login. Change: session data as typed attributes with explicit load/save around the request pipeline rather than a mutable bag; async-safe. Drop: untyped bag, global helper. For API/service use cases, sessions are optional; make this a separate module.

---

## 2.7 Cache (including tags and atomic locks)

**(a)** Key-value cache with TTL across multiple stores, `remember` (compute on miss), counters, tags to flush groups, stale-while-revalidate (`flexible`), and atomic locks.

**(b) Contract (verified).**

```php
interface Store {                                   // implemented by drivers
    public function get($key);  public function many(array $keys);
    public function put($key, $value, $seconds);  public function putMany(array $values, $seconds);
    public function increment($key, $value = 1); public function decrement($key, $value = 1);
    public function forever($key, $value);  public function touch($key, $seconds);  // touch new in 13
    public function forget($key);  public function flush();  public function getPrefix();
}
interface Repository extends CacheInterface {       // user-facing, PSR-16 compatible
    public function pull($key, $default = null);  public function put($key, $value, $ttl = null);
    public function add($key, $value, $ttl = null);
    public function remember($key, $ttl, Closure $callback);  public function rememberForever($key, Closure $callback);
    public function sear($key, Closure $callback);  public function forget($key);  public function getStore();
}
interface Factory { public function store($name = null); }
interface LockProvider {                            // stores that can lock
    public function lock($name, $seconds = 0, $owner = null);
    public function restoreLock($name, $owner);
}
```

**(c) Drivers (verified in `CacheManager`).** `array`, `file`, `database`, `redis`, `memcached`, `dynamodb`, `apc`, `null`, `session`, `storage` (on a filesystem disk), `octane` (Swoole table), `failover` (ordered list of other stores, emits `CacheFailedOver`). Wrapper `memo()` gives a per-request memoised store (`MemoizedStore`). Custom: `Cache::extend('name', fn ($app, array $config) => Cache::repository(new MyStore))`: implement `Store` (plus `LockProvider` for locks; plus extend `TaggableStore` for tags).

**(d) Config.** See 1.4.2. Top-level `prefix`, `serializable_classes` (v13 security setting: restricts which classes may be unserialised from cache; default `false` in the example), `limiter` (store used by the rate limiter).

**(e) Layered.**
- **Tags**: `Cache::tags(['people','artists'])->put(...)` and `->flush()`. Only stores extending `TaggableStore` support them (`array`, `redis`, `memcached`, `apc`, `null`); others throw `BadMethodCallException('This cache store does not support tagging.')` (verified). Not supported by `file`, `database`, `dynamodb` (per docs).
- **Atomic locks**: see 2.8.
- `Cache::flexible($key, [fresh, stale], $callback)` stale-while-revalidate (verified `Repository::flexible`); `Cache::withoutOverlapping(...)` helper (verified).
- Events for every operation (`CacheHit`, `CacheMissed`, `KeyWritten`, `KeyForgotten`, ...), which Telescope/Pulse consume.
- Rate limiter built on cache (2.9). Scheduler mutexes and unique-job locks also use cache locks.

**(f) Dynamism.** Values are `serialize()`d PHP (hence `serializable_classes` allow-list: unserialising arbitrary classes from a shared cache is an object-injection vector); keys are strings; `__call` proxy to default store; `Closure` callbacks for `remember`.

**(g) Transfer.** Keep: `Store` (raw, driver-implemented) vs `Repository` (user-facing, adds remember/pull/events/tags) split; named stores; failover store; events; `touch`; stale-while-revalidate. Change: typed values via a codec parameter (`Cache<K,V>` with explicit serializer, JSON/bytes), never native object serialisation; tags as a capability interface (`TaggableStore`) discoverable at compile time rather than a runtime exception; locks as a separate contract (2.8) not a side-capability of the cache store. Drop: PHP object serialisation, the `__call` proxy.

---

## 2.8 Locks (`Cache::lock`)

**(a)** A distributed mutual-exclusion primitive built on cache stores: acquire a named lock with a TTL, run a callback under it, block with a timeout, and release only if you own it. Used directly and by `WithoutOverlapping` job middleware, `ShouldBeUnique` jobs, scheduler `onOneServer` / `withoutOverlapping`, `Cache::withoutOverlapping`, and session blocking.

**(b) Contract (verified).**

```php
interface Lock {
    public function get($callback = null);              // try once; if callback given, run it and release in finally
    public function block($seconds, $callback = null);  // retry until timeout; throws LockTimeoutException
    public function release();                          // only releases if this owner holds it
    public function owner();                            // owner token string
    public function forceRelease();                     // ignore owner
}
interface LockProvider {
    public function lock($name, $seconds = 0, $owner = null);   // seconds = TTL; 0 = no expiry
    public function restoreLock($name, $owner);                 // rebuild a lock object across processes from its owner token
}
interface CanFlushLocks { public function flushLocks(): bool; public function hasSeparateLockStore(): bool; }
```

The abstract class adds, outside the contract: `refresh($seconds = null)` (throws unless the driver supports it, Redis does), `isLocked()`, `isOwnedByCurrentProcess()`, `isOwnedBy($owner)`, `betweenBlockedAttemptsSleepFor($ms)`.

**Mechanics (verified `Cache/Lock.php`, `RedisLock.php`, `DatabaseLock.php`).**
- Constructor `(name, seconds, owner = null)`; if no owner is supplied a random string is generated. **The owner token is the identity of the holder**; two `lock()` calls for the same name get different owners.
- `acquire()` per driver: Redis `SET name owner EX seconds NX` (or `SETNX` when no TTL); database inserts a row `(key, owner, expiration)` and on unique-violation updates the row only if owner matches or expiration has passed; plus a probabilistic prune of expired rows (`lock_lottery`, default `[2,100]`). File, Memcached, DynamoDB, array, no-op (`NoLock`) variants exist.
- `release()` per driver: Redis uses a **Lua compare-and-delete** (`LuaScripts::releaseLock`) so a holder whose lock expired cannot delete a successor's lock; `refresh` uses a Lua compare-and-expire.
- `block($seconds)` is a **polling loop**: `acquire()` every 250 ms (`sleepMilliseconds`, adjustable) until the deadline, then throws `LockTimeoutException`. No queueing/fairness, no wake-up notification.
- Passing the owner token (`$lock->owner()`) to another process and calling `Cache::restoreLock($name, $owner)->release()` hands ownership across processes (used for queued jobs that release a lock acquired in the web request).
- There is also `Cache\Limiters\ConcurrencyLimiter` (`Cache::...` semaphore with N slots, implemented as N locks `name1..nN`, verified), used by `Redis::funnel()`.

**Not provided / semantics to note.** No fencing tokens (no monotonically increasing token the protected resource can check), so a paused holder past its TTL can still act; no re-entrancy; no fairness; lock safety is only as strong as the single Redis/DB node (no Redlock); TTL `0` means "never expires" and a crashed holder leaks the lock until `forceRelease`.

**(c) Drivers/extension.** Any `Store` that also implements `LockProvider`: `redis`, `memcached`, `dynamodb`, `database`, `file`, `array`, `null`, `memoized`, `failover` (verified class declarations). Custom: implement `LockProvider` on a custom store and return a `Lock` subclass. Separate lock connection/table is configurable (`lock_connection`, `lock_table`).

**(d) Config.** Rides on cache stores: `'redis' => ['driver'=>'redis','connection'=>'cache','lock_connection'=>'default']`, `'database' => [..., 'lock_table' => 'cache_locks', 'lock_lottery' => [2,100], 'lock_timeout' => 86400]`.

**(e) Layered.** `Cache::lock('x', 10)->get(fn () => ...)`, `->block(5, fn () => ...)`, `Cache::withoutOverlapping`, job middleware `WithoutOverlapping` (key by job, releases job back to queue with delay), `ShouldBeUnique` (lock held until job finishes or starts), scheduler `->withoutOverlapping()` / `->onOneServer()` (`CacheSchedulingMutex`, `CacheEventMutex`), `Isolatable` commands (`CacheCommandMutex`).

**(f) Dynamism.** Coupling to cache stores means choosing a lock implementation by choosing a cache driver; the lock lifetime is a PHP request or job, release in `finally`. `Str::random` owner. Nothing deeply dynamic; this is one of the cleanest portable pieces.

**(g) Transfer.** Keep: the `Lock` contract with owner token, `get(callback)` / `block(timeout, callback)`, compare-and-delete release, `restoreLock`. Change: make `LockProvider` an independent service (`LockManager` with named lock stores), not a cache side-capability; add a monotonically increasing **fencing token** to the acquired handle; make `block` use async waiting/notification where the backend allows (Postgres advisory locks, Redis pub/sub) instead of 250 ms polling; return a typed `Result<LockHandle, Timeout>`; add `refresh` to the contract; use a scope/RAII (`try-with-resources`, `Drop`, `defer`) so release is guaranteed. Drop: the "TTL 0 = forever" default, silent leaks.

---

## 2.9 Rate limiting

**(a)** Throttle by key: a general `RateLimiter` service over the cache, named limiters declared once and referenced by name from the `throttle:` middleware, queue job middleware, and scheduled work.

**(b) Contract.** There is **no interface**; `Illuminate\Cache\RateLimiter` is a concrete class (verified, bound as `RateLimiter::class`). Key methods:

```php
class RateLimiter {
    public function __construct(Cache $cache);                       // a cache Repository
    public function for($name, Closure $callback);                   // register a named limiter
    public function limiter($name);
    public function attempt($key, $maxAttempts, Closure $callback, $decaySeconds = 60);
    public function tooManyAttempts($key, $maxAttempts);
    public function hit($key, $decaySeconds = 60);  public function increment($key, $decaySeconds = 60, $amount = 1);
    public function attempts($key);  public function remaining($key, $maxAttempts);
    public function availableIn($key);  public function clear($key);  public function resetAttempts($key);
}
final class Limit {   // Cache/RateLimiting/Limit.php
    public static function perSecond($max, $decaySeconds = 1);  public static function perMinute($max, $decayMinutes = 1);
    public static function perMinutes($decayMinutes, $max);     public static function perHour($max, $decayHours = 1);
    public static function perDay($max, $decayDays = 1);
    public function by($key);  public function response(callable $callback);  public function after($callback);
}
// also: Limit::none() / Unlimited, GlobalLimit
```

**(c) Drivers.** Whatever cache store `cache.limiter` points to; Redis-optimised variants `ThrottleRequestsWithRedis`, `RateLimitedWithRedis` (verified present). The algorithm is a **fixed window counter** (`hit` increments a key with TTL = decay; `availableIn` derived from the stored timer key). No sliding window or token bucket in core. Custom storage = custom cache store.

**(d) Config.** Code: in a provider's `boot()`:

```php
RateLimiter::for('api', fn (Request $request) =>
    Limit::perMinute(60)->by($request->user()?->id ?: $request->ip()));
// multiple limits: return [Limit::perMinute(500), Limit::perMinute(3)->by($request->input('email'))];
```

and on a route `->middleware('throttle:api')`.

**(e) Layered.** Per-user/IP/segment limits, multiple limits per limiter, custom 429 response, `after` callbacks, `Limit::perDay`, job middleware `RateLimited('name')` and `ThrottlesExceptions`, login throttling in auth packages, `Schedule` tie-ins.

**(f) Dynamism.** Named limiters are closures taking a `Request`; keys are strings with an MD5-style cleanup (`cleanRateLimiterKey`); counters stored as serialised ints in the cache; `Limit` is a plain value object (portable).

**(g) Transfer.** Keep: named limiters returning one or more `Limit` values (max, window, key), key derivation from request context, and the middleware that applies them; the `Limit` value object. Change: put a `RateLimiterStore` interface (atomic `hit(key, window) -> (count, resetAt)`) behind the service so non-cache backends and sliding-window/token-bucket algorithms can be used; return a typed `Decision {allowed, remaining, retryAfter}`. This is also the natural seed for the **metering/quota gap** (Part 3): `Limit::perDay` is a counter with TTL, not a metered, queryable, persistent usage ledger.

---

## 2.10 Queues and jobs

**(a)** Defer work to background workers via a named queue connection. Jobs are classes; dispatch with `ProcessPodcast::dispatch($podcast)`. Provides retries, backoff, timeouts, delays, failed-job storage, batches, chains, middleware, unique jobs, encryption, rate limiting and worker supervision commands.

**(b) Contract (verified).**

```php
interface Queue {                                    // Contracts/Queue/Queue
    public function size($queue = null);  public function pendingSize($queue = null);
    public function delayedSize($queue = null);  public function reservedSize($queue = null);
    public function push($job, $data = '', $queue = null);
    public function pushOn($queue, $job, $data = '');
    public function pushRaw($payload, $queue = null, array $options = []);
    public function later($delay, $job, $data = '', $queue = null);
    public function bulk($jobs, $data = '', $queue = null);
    public function pop($queue = null);                         // returns a Job
    public function getConnectionName();  public function setConnectionName($name);
}
interface Job {                                      // a reserved message
    public function uuid();  public function payload();  public function fire();
    public function release($delay = 0);  public function delete();  public function attempts();
    public function hasFailed();  public function fail($e = null);
    public function maxTries();  public function timeout();  public function retryUntil();  public function getRawBody();
}
interface Factory { public function connection($name = null); }
// Marker interfaces on a job class: ShouldQueue, ShouldBeUnique, ShouldBeUniqueUntilProcessing,
// ShouldBeEncrypted, ShouldQueueAfterCommit
interface QueueingDispatcher extends Dispatcher {            // Bus
    public function batch($jobs);  public function findBatch(string $batchId);  public function dispatchToQueue($command);
}
```

**(c) Drivers (verified in the example `config/queue.php` and `Queue/`).** `sync`, `database`, `redis`, `sqs`, `beanstalkd`, `null`, plus v13's `deferred` (run after the response in-process), `background` (`BackgroundQueue`), and `failover` (ordered list of connections, `FailoverQueue`). Custom driver: `Queue::extend('name', fn () => new MyConnector)` / `addConnector`, a **Connector** whose `connect(array $config)` returns a `Queue`; also a `Job` subclass for the reserved-message wrapper (`Queue/Jobs/{Database,Redis,Sqs,Sync,Beanstalkd}Job`).

**(d) Config (verified).**

```php
'default' => env('QUEUE_CONNECTION', 'database'),
'connections' => [
  'redis' => ['driver'=>'redis','connection'=>'default','queue'=>'default','retry_after'=>90,'block_for'=>null,'after_commit'=>false],
  'sqs'   => ['driver'=>'sqs','key'=>..,'secret'=>..,'prefix'=>..,'queue'=>'default','region'=>..,'after_commit'=>false],
  'failover' => ['driver'=>'failover','connections'=>['database','deferred']],
],
'batching' => ['database'=>..., 'table'=>'job_batches'],
'failed'   => ['driver'=>'database-uuids','database'=>..., 'table'=>'failed_jobs'],
```

v13 adds central routing: `Queue::route(ProcessPodcast::class, connection: 'redis', queue: 'podcasts')` (`QueueRoutes`, release notes).

**(e) Layered.**
- **Job class** declares behaviour as properties or, in v13, attributes: `#[Tries]`, `#[Backoff]`, `#[Timeout]`, `#[MaxExceptions]`, `#[FailOnTimeout]`, `#[Queue]`, `#[Connection]`, `#[Delay]`, `#[UniqueFor]`, `#[DebounceFor]`, `#[WithoutRelations]`, `#[DeleteWhenMissingModels]` (verified `Queue/Attributes/`). Methods: `handle()`, `failed(Throwable)`, `middleware()`, `retryUntil()`, `backoff()`, `uniqueId()`.
- **Chains**: `Bus::chain([A, B, C])->dispatch()`: sequential, abort on failure, `catch`.
- **Batches**: `Bus::batch([...])->then()->catch()->finally()->dispatch()`; `Batch` with progress, cancel, `allowFailures`; stored via `BatchRepository` (database or DynamoDB, verified); jobs use `Batchable` and `SkipIfBatchCancelled` middleware.
- **Job middleware** (verified `Queue/Middleware/`): `RateLimited`, `RateLimitedWithRedis`, `ThrottlesExceptions`, `WithoutOverlapping`, `SkipIfBatchCancelled`, `Skip`, `FailOnException`.
- **Unique jobs**: lock (cache lock) keyed by `uniqueId()` for the job's lifetime (`UniqueLock`); `DebounceLock` also present.
- **Failed jobs**: `queue:failed`, `queue:retry`, `queue:flush`, `FailedJobProviderInterface` implementations (database-uuids, file, DynamoDB, null).
- **After-commit dispatch**: job (or connection) waits for the surrounding DB transaction to commit (`afterCommit`, `ShouldQueueAfterCommit`, `DatabaseTransactionsManager`), the framework's answer to "dispatch before commit loses data" without a full outbox.
- **Worker** (`Queue/Worker.php`): `queue:work`, graceful stop signals, `queue:restart` through cache signal, memory/time limits, `Interruptible` contract.
- Encrypted payloads (`ShouldBeEncrypted`), `Context` propagated into jobs (2.24), Horizon dashboard/supervisor for Redis queues (not verified).

**(f) PHP dynamism (this is the heaviest area).**
- **The payload is `serialize($job)`** (verified in `Queue::createObjectPayload`: `serialize(clone $job)`), wrapped with `'job' => 'Illuminate\Queue\CallQueuedHandler@call'`, `commandName` = the PHP class name, `command` = the serialised object. The worker `unserialize()`s and calls `handle()` via the container. Consequences: the wire format is a PHP-specific object graph keyed by **fully qualified class name**; renaming a class breaks in-flight jobs; the payload cannot be read by another language; deserialising is an object-injection surface (the reason `serializable_classes` allow-lists were added to cache).
- **Models in jobs**: the `SerializesModels` trait (verified, uses `ReflectionClass`/`ReflectionProperty`) replaces each Eloquent model property with a `ModelIdentifier` (class + id + loaded relations) and re-fetches from the DB on unserialise. So a job sees the **current** row, not the state at dispatch; a deleted row aborts the job (`DeleteWhenMissingModels`).
- **Closures**: `dispatch(function () {...})` is serialised with `laravel/serializable-closure` (installed; `CallQueuedClosure`), which captures source code and `use` variables. Package-version-sensitive and security-sensitive (signed with the app key).
- Handler resolution by string `Class@method`; `handle()` dependencies injected by reflection.
- `Bus::map` can map command classes to handler classes by class-string.

**(g) Transfer.** Keep: `Queue` (connection-level: push/later/bulk/pop/size) vs `Job` (a leased message with release/delete/fail/attempts) split; named connections + failover; a **Connector** concept; job metadata (tries, backoff, timeout, queue, delay, unique key) declared on the job type; job middleware pipeline; chains and batches with a persistent batch repository; unique jobs via a lock; after-commit dispatch (see Part 3 outbox gap); failed-job store + retry commands; worker graceful-stop contract. Change: **message payload is an explicit versioned schema (JSON/Protobuf/Avro) with a stable string `type` name and a registry type↔handler**, never a language object serialisation; handlers receive ids/value payloads and load aggregates themselves from a repository; no closure jobs; delivery semantics (at-least-once) and idempotency keys documented in the contract. Drop: `serialize(clone $job)`, `SerializesModels`, closure serialisation, string `Class@method`, "model re-fetched on unserialise" semantics.

---

## 2.11 Events and listeners

**(a)** In-process publish/subscribe. Events are objects (or strings); listeners are classes, closures or subscribers; listeners can be queued; framework subsystems emit their own events (cache, auth, mail, queue, migrations, model lifecycle).

**(b) Contract (verified).**

```php
interface Dispatcher {   // Contracts/Events/Dispatcher
    public function listen($events, $listener = null);
    public function hasListeners($eventName);
    public function subscribe($subscriber);
    public function until($event, $payload = []);            // dispatch, stop at first non-null result
    public function dispatch($event, $payload = [], $halt = false);
    public function push($event, $payload = []);             // deferred "pushed" events
    public function flush($event);  public function forget($event);  public function forgetPushed();
}
// Markers: ShouldQueue (listener), ShouldDispatchAfterCommit (event), ShouldHandleEventsAfterCommit (listener),
//          ShouldBeDiscovered (static shouldBeDiscovered(): bool, v13), ShouldBroadcast (2.12)
```

**(c) Extension.** No drivers. Listener forms: class with `handle(Event $e)` or `__invoke`, `Class@method`, closures, wildcard listeners (`'user.*'`), subscribers (`subscribe(Dispatcher)`). Queued closures (`QueuedClosure`, `InvokeQueuedClosure`). `Dispatcher::setQueueResolver`.

**(d) Config.** None. Listeners are **auto-discovered** (default in recent versions): `Foundation/Events/DiscoverEvents` scans the listeners directory with reflection and registers by the type-hint of `handle()`'s first parameter; can be cached (`event:cache`). Or registered manually in a provider.

**(e) Layered.** Queued listeners (`implements ShouldQueue`, `$queue`, `$delay`, `$tries`), after-commit listeners, `Dispatchable` trait (`Event::dispatch()` static), `event:list`, Eloquent model events (2.18), `Event::fake()`, `dispatchIf`, deferred dispatch (`defer()`), event discovery attributes.

**(f) Dynamism.** Auto-discovery by reflection of method signatures; listener strings; wildcard matching by string; payload may be a string name + array (untyped) or an object; queued listeners serialise the event object (same `serialize` issue as 2.10); `Dispatchable` trait uses `static::dispatch()` via `new static(...$args)`.

**(g) Transfer.** Keep: object-typed events, `listen(EventType, Listener)`, subscriber grouping, queued/async listeners, after-commit semantics, framework events as an observability seam. Change: typed `EventBus` with generics (`on<E>(Listener<E>)`), compile-time registration (no directory scan), explicit separation of **in-process application events** from **domain events** and from **integration events** (see Part 3 domain layer); asynchronous listeners publish a versioned message, not a serialised object. Drop: string events and wildcard, `until`, `push/flush` pairs, discovery-by-reflection.

---

## 2.12 Broadcasting

**(a)** Push server events to browsers (WebSockets) on public, private and presence channels, with channel authorisation callbacks. Server side: events that implement `ShouldBroadcast`. Client side: Laravel Echo (JS).

**(b) Contract (verified).**

```php
interface Broadcaster {                              // the driver
    public function auth($request);
    public function validAuthenticationResponse($request, $result);
    public function broadcast(array $channels, $event, array $payload = []);
}
interface Factory { public function connection($name = null); }
interface ShouldBroadcast { public function broadcastOn(); }     // returns Channel / PrivateChannel / PresenceChannel list
// also ShouldBroadcastNow (skip the queue), HasBroadcastChannel (models), ShouldRescue
```

**(c) Drivers (verified `BroadcastManager`).** `reverb` (first-party WebSocket server; not verified in detail), `pusher`, `ably`, `redis`, `log`, `null`. Extension: `Broadcast::extend('name', fn ($app, array $config) => new MyBroadcaster)`, implementing `Broadcaster`.

**(d) Config.** `config/broadcasting.php`: `'default' => env('BROADCAST_CONNECTION')`, `'connections' => ['reverb' => [...], 'pusher' => [...], 'ably' => [...], 'log'..]`. Channel authorisation in `routes/channels.php`: `Broadcast::channel('orders.{orderId}', fn ($user, $orderId) => $user->id === Order::findOrNew($orderId)->user_id)`.

**(e) Layered.** Broadcast events go through the queue by default (`BroadcastEvent` job); model broadcasting (`BroadcastsEvents` on Eloquent), anonymous events (`Broadcast::on(...)`), encrypted private channels, `toOthers()` (exclude sender via socket id header), client events ("whisper"), notifications over broadcast (2.14), `broadcasting/auth` route.

**(f) Dynamism.** Event name defaults to class name; payload defaults to **all public properties** of the event object (reflection); channel authorisers are closures with route-pattern parameters; models as broadcast sources via trait.

**(g) Transfer.** Keep: `Broadcaster` driver contract (`broadcast(channels, event, payload)` + `auth`), channel taxonomy (public/private/presence), channel authorisation callbacks, queue-backed fan-out. Change: explicit payload mapping (`toBroadcast()` returning a typed value) rather than public-property reflection; typed channel names. Drop: model-event broadcasting through ORM traits. Note this is optional for service back-ends; make it an add-on module.

---

## 2.13 Task scheduling

**(a)** Declare recurring work in code; one cron entry (`* * * * * php artisan schedule:run`, or the long-running `schedule:work`) triggers due tasks. Supports commands, jobs, shell commands, closures.

**(b) Contract.** There is **no interface**; the scheduler is concrete classes (`Console/Scheduling/Schedule.php`, `Event.php`, `CallbackEvent.php`). Key API (verified):

```php
class Schedule {
    public function call($callback, array $parameters = []);   public function command($command, array $parameters = []);
    public function job($job, $queue = null, $connection = null);  public function exec($command, array $parameters = []);
    public function group(Closure $events);  public function dueEvents($app);  public function useCache($store);
    // fluent frequency/constraint methods via ManagesFrequencies/ManagesAttributes:
    //   ->everyMinute() ->hourly() ->dailyAt('13:00') ->cron('...') ->timezone(...)
    //   ->withoutOverlapping($expiresAt = 1440) ->onOneServer() ->runInBackground() ->evenInMaintenanceMode()
}
interface EventMutex / SchedulingMutex   // store-backed mutex strategies (CacheEventMutex, CacheSchedulingMutex)
```

**(c) Extension.** Mutex implementations (`useCache($store)`) selects the cache store used for locks; no other drivers.

**(d) Config.** Code in `routes/console.php`: `Schedule::command('reports:daily')->dailyAt('02:00')->onOneServer()->withoutOverlapping();`.

**(e) Layered.** Overlap prevention and single-server execution via cache locks, hooks (`before`, `after`, `onSuccess`, `onFailure`, `pingBefore` health pings, email output), `schedule:list`, `schedule:test`, `schedule:pause/resume`, maintenance-mode awareness, task output to files, environment constraints, `ScheduleFinish` events.

**(f) Dynamism.** Closures held in memory (no serialisation needed since evaluated each minute); command strings parsed by Symfony Console; frequency methods are fluent mutators on `Event`; `__call` for pending attributes.

**(g) Transfer.** Keep: schedule as code, cron expression + timezone, `onOneServer` via a lock, no-overlap via a lock, jobs as schedulable units, schedule list/test commands. Change: schedule entries are typed (`Schedule.every(Duration)`/`cron(...)` + `Task` interface), run by an in-process scheduler with leader election using the lock contract instead of an external cron minute tick; missed-run policy explicit. Drop: shell-string commands, closure tasks without names.

---

## 2.14 Mail

**(a)** Compose and send email through pluggable transports. A **Mailable** class represents one message type (subject, recipients, view/markdown, attachments); mail can be sent immediately or queued; multiple named mailers can be configured; templates via Blade or Markdown components.

**(b) Contract (verified).**

```php
interface Mailer {                                   // Contracts/Mail/Mailer
    public function to($users);  public function cc($users);  public function bcc($users);
    public function raw($text, $callback);
    public function send($view, array $data = [], $callback = null);
    public function sendNow($mailable, array $data = [], $callback = null);
}
interface Mailable {
    public function send($mailer);  public function queue(Queue $queue);  public function later($delay, Queue $queue);
    public function cc($address, $name = null);  public function bcc(...);  public function to(...);
    public function locale($locale);  public function mailer($mailer);
}
interface Factory { public function mailer($name = null); }
interface MailQueue { public function queue($view, $queue = null); public function later($delay, $view, $queue = null); }
```

**(c) Transports (verified in `MailManager::create*Transport`).** `smtp`, `sendmail`, `ses`, `ses-v2`, `postmark`, `mailgun`, `resend`, `cloudflare`, `mail` (PHP), `log`, `array`, and two composite transports: **`failover`** and **`roundrobin`**, each listing other mailers by name. Underneath is **Symfony Mailer**: the manager builds a Symfony `TransportInterface`. Custom transport: `Mail::extend('name', fn (array $config) => new MyTransport(...))` where the creator returns a Symfony transport (note: creator signature differs from Cache/Filesystem; no `$app` argument).

**(d) Config (verified).**

```php
'default' => env('MAIL_MAILER', 'log'),
'mailers' => [
  'smtp' => ['transport'=>'smtp','host'=>..,'port'=>..,'username'=>..,'password'=>..],
  'ses'  => ['transport'=>'ses'],
  'failover'   => ['transport'=>'failover','mailers'=>['smtp','log'],'retry_after'=>60],
  'roundrobin' => ['transport'=>'roundrobin','mailers'=>['ses','postmark'],'retry_after'=>60],
],
'from' => ['address'=>env('MAIL_FROM_ADDRESS'),'name'=>env('MAIL_FROM_NAME')],
```

Global addresses `from`, `reply_to`, `to` (catch-all for debugging), `return_path` can be set per mailer (verified in `resolve`).

**(e) Layered.** Mailable structure: `envelope()` (subject/from/tags/metadata: `Mailables/Envelope`), `content()` (`view`, `markdown`, `text`: `Mailables/Content`), `attachments()` (`Attachment::fromPath/fromStorage/fromData`), `headers()`; Markdown mail with themed components (`Mail/Markdown.php`) and publishable templates; queueing (`implements ShouldQueue`, `->queue()`, `->later()`, `afterCommit`); `Attachable` contract for objects convertible to attachments; localisation (`->locale('es')`, user locale preference); previews (return a mailable from a route); `Mail::fake()` with `assertSent/assertQueued`; events `MessageSending`/`MessageSent`; `Mail::mailer('postmark')->to(...)->send(...)`; delivery metadata (tags, metadata) is passed to providers that support it.

**(f) Dynamism.** `Mail::to($user)->send(new Welcome($user))` builds a `PendingMail` fluent object; Blade views compiled to PHP; mailables queued via `SendQueuedMailable` (serialised object, with `SerializesModels`); `Mail::to($users)` accepts strings, arrays, objects with `email` property or `HasLocalePreference` by duck typing (`Mailer::to`).

**(g) Transfer.** Keep: named mailers, composite transports (**failover** and **roundrobin** with `retry_after`), a Mailable as a typed message description separate from transport, envelope/content/attachments decomposition, global from/reply-to/redirect-to, queue integration, `log` and `array` transports, a recording fake. Change: Mailable is an immutable value (`MailMessage`) built by a factory; transport contract is `Transport.send(MailMessage) -> DeliveryResult{provider_message_id}` returning a **delivery receipt** (Laravel returns an opaque `SentMessage`, which weakens delivery tracking); template engine is pluggable (type-checked templates or a pre-rendering step). Drop: view-name strings, `Mail::to()` accepting any shape.

---

## 2.15 Notifications

**(a)** A **Notification** class describes one user-facing message and the **channels** it goes through; the application calls `$user->notify(new InvoicePaid($invoice))` or sends to many. Each channel renders the message in its own format (`toMail`, `toDatabase`, `toBroadcast`, `toVonage`, `toSlack`).

**(b) Contract.**

```php
interface Dispatcher {                              // Contracts/Notifications/Dispatcher (verified)
    public function send($notifiables, $notification);
    public function sendNow($notifiables, $notification, ?array $channels = null);
}
interface Factory {                                 // Contracts/Notifications/Factory
    public function channel($name = null);  public function send($notifiables, $notification);
    public function sendNow($notifiables, $notification);
}
```

**There is no `Channel` interface.** A channel is any class with the duck-typed method (verified in `DatabaseChannel` and the docs):

```php
class VoiceChannel {
    public function send(object $notifiable, Notification $notification): void {
        $message = $notification->toVoice($notifiable);   // method name derived by convention from the channel
    }
}
// The Notification class:
public function via(object $notifiable): array { return ['mail', 'database', VoiceChannel::class]; }
public function toMail(object $notifiable): MailMessage { ... }
// The notifiable (usually User) routes: public function routeNotificationForVonage($notification) { return $this->phone; }
```

**(c) Channels shipped.** In core (verified `Notifications/Channels/`): `mail` (`MailChannel`), `database` (`DatabaseChannel`, `notifications` table via `DatabaseNotification` model), `broadcast` (`BroadcastChannel`). **Separate first-party packages** (docs 13.x): Vonage SMS (`laravel/vonage-notification-channel`), Slack (`laravel/slack-notification-channel`). Community channels via laravel-notification-channels.com (docs). Custom channel: a class as above, referenced in `via()` by class name; `ChannelManager::createDriver` falls back to `class_exists($driver) → container->make($driver)` (verified), and `Notification::extend` is available since `ChannelManager extends Manager`.

**(d) Config.** None in core for channels; service credentials in `config/services.php` (for example Vonage key/secret/sms_from). Mail channel uses the `mail` config.

**(e) Layered.** **On-demand notifications**: `Notification::route('mail','a@b.c')->route('vonage','5555555555')->route('slack','#alerts')->notify(new InvoicePaid($invoice))` (verified in docs; implemented by `AnonymousNotifiable`). Queueing (`ShouldQueue` + `Queueable`; each channel is queued separately; per-channel queues `viaQueues()`), delays, `shouldSend()` per channel, locale, `Notifiable` trait (`notify`, `routeNotificationFor`, `notifications` relationship, `readNotifications`, `unreadNotifications`), events (`NotificationSending`, `NotificationSent`, `NotificationFailed`), `Notification::fake()` with `assertSentTo`, `HasDatabaseNotifications`, `NotificationSender`.

**(f) Dynamism.** Method-name convention (`'to'.ucfirst($channel)`) bridges channel and notification; routing by `routeNotificationFor{Channel}` methods discovered by `method_exists` on the notifiable; channel names are strings; class-name-as-channel via `class_exists`; queued notifications are serialised objects.

**(g) Transfer.** Keep: the split between **what** (Notification: content per channel), **who** (Notifiable routing: address per channel), and **how** (Channel: delivery), on-demand recipients, `via()` per-recipient channel selection, per-channel queueing, `shouldSend`. Change: introduce a real `NotificationChannel<M>` interface (`channelName`, `send(recipient, message) -> DeliveryResult`), typed per-channel message types (`MailMessage`, `SmsMessage`, `ChatMessage`) and a typed `Recipient` with per-channel addresses (a map or sealed type), instead of method-name conventions. This is also where the **unified outbound messaging abstraction** gap sits (Part 3). Drop: `to<Channel>()` naming, `routeNotificationFor<Channel>()` naming.

---

## 2.16 Filesystem / storage

**(a)** One API for local and cloud object storage across named **disks** (`Storage::disk('s3')->put(...)`), with visibility (public/private), streaming, temporary URLs, directory listing, and URL generation.

**(b) Contract (verified).**

```php
interface Filesystem {
    public function path($path);  public function exists($path);
    public function get($path);   public function readStream($path);
    public function put($path, $contents, $options = []);      public function putFile($path, $file = null, $options = []);
    public function putFileAs($path, $file, $name = null, $options = []);
    public function writeStream($path, $resource, array $options = []);
    public function getVisibility($path);  public function setVisibility($path, $visibility);
    public function prepend($path, $data); public function append($path, $data);
    public function delete($paths);  public function copy($from, $to);  public function move($from, $to);
    public function size($path);  public function lastModified($path);
    public function files($directory = null, $recursive = false);  public function allFiles($directory = null);
    public function directories($directory = null, $recursive = false);
    public function makeDirectory($path);  public function deleteDirectory($directory);
}
interface Cloud extends Filesystem { public function url($path); }
interface Factory { public function disk($name = null); }
```

**(c) Drivers (verified `FilesystemManager`).** `local`, `s3` (and S3-compatible via `endpoint`), `ftp`, `sftp`, and **`scoped`** (a prefix-limited view over another disk; v11+). All built on **Flysystem** adapters (`createFlysystem(FlysystemAdapter $adapter, array $config)`), with `read-only` wrapper support. Custom: `Storage::extend('name', fn ($app, array $config) => new FilesystemAdapter(new Filesystem($flysystemAdapter, $config), $flysystemAdapter, $config))`, i.e. supply a Flysystem adapter (Azure, GCS, SFTP variants exist as community adapters).

**(d) Config (verified).**

```php
'default' => env('FILESYSTEM_DISK', 'local'),
'disks' => [
  'local'  => ['driver'=>'local','root'=>storage_path('app/private'),'serve'=>true,'throw'=>false,'report'=>false],
  'public' => ['driver'=>'local','root'=>storage_path('app/public'),'url'=>'.../storage','visibility'=>'public'],
  's3'     => ['driver'=>'s3','key'=>..,'secret'=>..,'region'=>..,'bucket'=>..,'url'=>..,'endpoint'=>..,'use_path_style_endpoint'=>false],
],
'links' => [public_path('storage') => storage_path('app/public')],   // storage:link symlinks
```

**(e) Layered.** Visibility (`public`/`private`), `temporaryUrl($path, $expiration)` (S3 pre-signed; local disks produce signed app URLs served by the framework route registered in `FilesystemServiceProvider::boot`), `temporaryUploadUrl`, streaming (`readStream`, `writeStream`, `download`, `response`), uploaded-file helpers (`$request->file('x')->store('dir', 's3')`), directory ops, checksums/mime/last-modified, `throw` option to convert silent false returns into exceptions, **scoped disks** (per-tenant or per-feature prefix of a bucket), cache driver over storage, `Storage::fake()`.

**(f) Dynamism.** Operations return `false`/strings/mixed depending on `throw` config (verified option) rather than typed results; path strings; the `Storage` facade; Flysystem adapter classes loaded by name; options as `array`.

**(g) Transfer.** Keep: named disks, one interface across backends, visibility, temporary URLs, **scoped** (prefix) disks, streaming, a `local` driver that doubles as the test fake, an adapter-level extension point. Change: return typed results/errors (always throw or always `Result`); typed `ObjectMetadata`; separate narrow interfaces (`ReadStore`, `WriteStore`, `UrlSigner`) so drivers can omit capabilities; streams as first-class types. Drop: `false`-on-error, option arrays.

---

## 2.17 Database layer (separate from Eloquent)

**(a)** A connection manager, a fluent query builder, a schema builder and migration runner, seeders and factories. Works without Eloquent.

**Connections (`Database/DatabaseManager.php`, `Connection.php`, `Connectors/`).**
- Named connections from config; drivers (connectors): **MySQL, MariaDB, PostgreSQL, SQLite, SQL Server** (verified `*Connector.php`, `*Connection.php`). `DB::connection('name')`, `DB::extend` for custom connection types.
- Each `Connection` wraps PDO with `select`, `selectOne`, `insert`, `update`, `delete`, `statement`, `unprepared`, `pretend(Closure)` (dry-run SQL), query listeners (`listen`), query-log, slow-query/cumulative-duration hooks.
- **Read/write splitting** (verified `ConnectionFactory`): config keys `read` and `write` (each a list of hosts) create two PDOs; reads use the read PDO unless `sticky` is set, in which case a connection that has written in the current request reads from the write PDO (`useWriteConnectionWhenReading`, `selectFromWriteConnection`).
- **Transactions** (`Concerns/ManagesTransactions.php`): `transaction(Closure, $attempts = 1)` (retries on deadlock via `DetectsConcurrencyErrors`), `beginTransaction/commit/rollBack`, nested levels with **savepoints**, `afterCommit($callback)`, `DatabaseTransactionsManager` (used by queue/event after-commit features).
- Lost-connection detection and reconnect (`DetectsLostConnections`, `LostConnectionDetector` contract).
- `Database/Capsule` for use without the framework.

**Query builder (`Database/Query/Builder.php`, `Grammars/`).** Fluent SQL: `DB::table('users')->where('active', 1)->join(...)->groupBy(...)->orderBy(...)->paginate()`, subqueries, CTEs, unions, `upsert`, `insertOrIgnore`, `lazy`/`chunk`/`cursor`, row locks (`lockForUpdate`, `sharedLock`), raw expressions (`Expression`), JSON column queries, v13 vector similarity (`whereVectorSimilarTo`, pgvector), one `Grammar` per DB. Bindings are always parameterised. Contract: `Contracts\Database\Query\Builder` is an empty marker (verified).

**Schema builder and migrations.** `Schema::create('users', function (Blueprint $table) { $table->id(); $table->string('email')->unique(); ... })` compiled by per-DB grammars; `Migration` classes with `up()`/`down()`; `Migrator` (`run`, `rollback`, `reset`, `pretend`), a `migrations` table repository, schema dump/load (`SchemaState` per DB, e.g. `PostgresSchemaState` uses `pg_dump`), `Migration::shouldRun()`, per-migration connection. Migration files are timestamp-named PHP classes.

**Seeders.** `Seeder` classes with `run()` and `call([...])`; `db:seed`.

**Factories.** `Factory` classes (`Eloquent/Factories/Factory.php`) with states, sequences, relationships; they are Eloquent-bound (`HasFactory`), i.e. they build **models**, not arbitrary records.

**(b) Contract.** `ConnectionInterface` (select/insert/update/delete/statement/transaction), `ConnectionResolverInterface`, `MigrationRepositoryInterface`; `Contracts/Database/LostConnectionDetector`, `ConcurrencyErrorDetector`. Not typed (PDO-shaped).

**(c) Drivers / extension.** Connectors per DB; `DB::extend`. Custom grammar by subclassing.

**(d) Config (example app, `config/database.php`).** `'default' => env('DB_CONNECTION')`, `'connections' => ['pgsql' => ['driver'=>'pgsql','host'=>..,'read'=>['host'=>[...]],'write'=>[...],'sticky'=>true, ...]]`, `'migrations' => ['table'=>'migrations','update_date_on_publish'=>true]`, `'redis' => [...]`.

**(e) Layered.** Pagination (`paginate`, `simplePaginate`, `cursorPaginate`), chunking/lazy streaming, `DB::listen`, `DB::pretend`, schema introspection (`Schema::getTables()`), `Schema::hasTable`, database events (`QueryExecuted`, `TransactionBeginning`, `MigrationsStarted`), model-less seeding, prunable models.

**(f) Dynamism.** Row results are `stdClass` objects or associative arrays; column names and operators are strings; the `Builder` uses `__call`, macros and dynamic `whereEmail($x)` methods; migrations are PHP classes included by file path and resolved by filename-derived class name (`Migrator::resolve($file)`).

**(g) Transfer.** Keep: named connections, read/write split with sticky reads, nested transactions with savepoints, `afterCommit` hooks, deadlock retry, `pretend`, a migration runner with up/down and a recorded history table, SQL grammars per dialect, schema dump. Change: queries typed to rows (typed projection mapping, or a SQL-first approach with generated code like sqlc / jOOQ / sqlx), migrations as SQL or typed DSL files with checksum verification (Laravel does not checksum), factories as plain test-data builders for domain objects. Drop: `stdClass` rows, magic `where<Column>` methods, migrations as arbitrary PHP code with global side effects, model-bound factories.

---

## 2.18 Eloquent (briefly)

**What it is.** An **active-record ORM** (`Database/Eloquent/Model.php`): a model class represents a table and a row, holds attributes in an array, persists itself (`$model->save()`), queries through a builder proxy (`User::where(...)->first()`), and carries relationships, casts, events and scopes.

**Features people lean on (file layout verified; behaviour from general knowledge).**
- **Relationships** (`Eloquent/Relations/`): `hasOne`, `hasMany`, `belongsTo`, `belongsToMany` (pivot), `hasManyThrough`, polymorphic (`morphTo`, `morphMany`, `morphToMany`), `hasOneOfMany`; eager loading (`with()`), `withCount`, lazy loading on property access (`$user->posts`).
- **Scopes**: global scopes (`Scope` interface, `HasGlobalScopes`) and local scopes (`#[LocalScope]` attribute or `scopeActive` naming).
- **Casts** (`Eloquent/Casts/`, `CastsAttributes` contract): attribute ↔ PHP value (array, JSON, enum, date, encrypted, value objects, custom casts).
- **Events/observers** (`HasEvents`): `creating/created/updating/updated/saving/saved/deleting/deleted/restoring/retrieved`; observer classes; `BroadcastsEvents`.
- **Soft deletes** (`SoftDeletes`, `SoftDeletingScope`): `deleted_at` column plus a global scope.
- Mass assignment guards (`$fillable`/`$guarded`), accessors/mutators, appends/hidden attributes, timestamps, UUID/ULID keys, `Prunable`, `QueueableEntity` (for jobs), `UrlRoutable` (for route binding), JSON serialisation.

**Known costs (these are structural, and the reasons the owner is dropping it).**
1. **Active-record coupling**: persistence, query, validation-ish casting, serialisation and domain behaviour are mixed in one class that extends a 4,000-line base. The model *is* the table, so the domain model mirrors the schema.
2. **Hidden queries and lazy loading**: a property access (`$post->author->name`) may issue SQL. Laravel ships guards for this (verified: `Model::preventLazyLoading()`, `preventSilentlyDiscardingAttributes()`, `preventAccessingMissingAttributes()`, `shouldBeStrict()`, and `LazyLoadingViolationException`), which confirms the problem is recognised; the default is off.
3. **N+1** is the default failure mode for relationship loops unless `with()` is used.
4. **Anemic or god models**: models become either bags of attributes with logic elsewhere, or large classes carrying all behaviour; events/observers hide control flow.
5. **Attributes are an untyped array** behind `__get`/`__set`; static analysis needs IDE helper files or extra tooling.
6. **Testing** requires a database (factories persist models) unless heavily mocked.
7. **Serialisation into jobs** by id (see 2.10) changes semantics.
8. **Global state**: static booted-model registries, static event dispatcher (`Model::setEventDispatcher`), static `unguarded`.

**Transfer.** Drop active record entirely. Keep ideas: casts as explicit codecs between column and value object; eager-loading as an explicit query-time decision; soft delete as an explicit repository filter; timestamps/ids via a base mixin or generator; observers as explicit domain-event publication (Part 3). Everything else (relationships, scopes) becomes repository query methods on aggregates.

---

## 2.19 Encryption and hashing

**(a)** `Crypt::encryptString` / `decrypt` (symmetric, authenticated) using the application key, and `Hash::make` / `check` for passwords.

**(b) Contract (verified).**

```php
interface Encrypter {
    public function encrypt($value, $serialize = true);  public function decrypt($payload, $unserialize = true);
    public function getKey();  public function getAllKeys();  public function getPreviousKeys();   // key rotation
}
interface StringEncrypter { public function encryptString($value); public function decryptString($payload); }
interface Hasher {
    public function info($hashedValue);  public function make($value, array $options = []);
    public function check($value, $hashedValue, array $options = []);  public function needsRehash($hashedValue, array $options = []);
}
```

**(c) Drivers.** `Encrypter`: ciphers `aes-128/256-cbc`, `aes-128/256-gcm` (verified `$supportedCiphers`, AEAD flag), key from `APP_KEY`, **previous keys** (`APP_PREVIOUS_KEYS`) for rotation. `HashManager extends Manager`: `bcrypt`, `argon`, `argon2id` (verified); `Hash::extend`.

**(d) Config.** `config/app.php`: `'cipher' => 'AES-256-CBC'`, `'key' => env('APP_KEY')`, `'previous_keys' => [...]`; `config/hashing.php`: `'driver' => 'bcrypt'`, `bcrypt.rounds`, `argon.*`, `rehash_on_login`.

**(e) Layered.** `encrypted` model casts, signed cookies/encrypted cookies, session encryption, queue payload encryption (`ShouldBeEncrypted`), `needsRehash` upgrade-on-login (`UserProvider::rehashPasswordIfRequired`), signed URLs (HMAC with app key), `env:encrypt`.

**(f) Dynamism.** `encrypt($value)` defaults to `serialize` of any PHP value, so decrypt returns arbitrary objects (the string variants avoid it); a single global `APP_KEY` is the root of trust for cookies, sessions, signed URLs, encrypted casts and encrypted jobs.

**(g) Transfer.** Keep: `Encrypter` contract with key-rotation support (`previous_keys`), AEAD ciphers, `Hasher` with `needsRehash`. Change: separate keys per purpose (cookie signing, data-at-rest, URL signing), pluggable KMS/envelope-encryption driver (not provided by Laravel), typed `encrypt(bytes) -> Ciphertext`. Drop: default object serialisation.

---

## 2.20 HTTP client

**(a)** A fluent outbound HTTP client (`Http::withToken($t)->acceptJson()->post($url, $data)`) with retries, timeouts, concurrent pools, response helpers and test stubbing.

**(b) Contract.** No contract interface; concrete `Http\Client\Factory`, `PendingRequest`, `Request`, `Response`, `Pool`, `Batch` (verified files). Built on Guzzle (not read).

**(c) Drivers.** None; Guzzle handler stack is the extension point (`withMiddleware`, `globalMiddleware`, `globalOptions`). Custom macros (`Http::macro`).

**(d) Config.** Code; per-service base URLs/credentials in `config/services.php` by convention.

**(e) Layered.** `retry($times, $sleep, $when)`, `timeout`, `connectTimeout`, `throw()` for `RequestException` on 4xx/5xx, `pool(fn ($pool) => [...])` concurrent requests, `Http::batch`, request/response events (consumed by Telescope/Pulse), `Http::fake([...])` URL-pattern stubs with sequences (`fakeSequence`), `preventStrayRequests()`, `assertSent`, `recorded()`, `Http::macro` named clients, `asForm`, `attach`, `sink`, signed/digest auth.

**(f) Dynamism.** Fluent mutation of one `PendingRequest`; response objects ArrayAccess to JSON; macros via `__call`.

**(g) Transfer.** Keep: stub-by-URL-pattern fake with `preventStrayRequests`, retry policy as data, `throw()` switch, events on every request for observability, named pre-configured clients. Change: typed request/response and a `HttpClient` interface; use the host language's established HTTP client and wrap it; typed deserialisation. This is the area most worth *not* rebuilding.

---

## 2.21 Logging

**(a)** PSR-3 logging with named **channels** that can be stacked, with contextual data.

**(b) Contract.** PSR-3 `LoggerInterface`; `LogManager implements LoggerInterface` (verified); `Contracts\Log\ContextLogProcessor extends Monolog ProcessorInterface`.

**(c) Drivers (channels, verified `LogManager`).** `single`, `daily`, `stack` (list of other channels), `slack`, `syslog`, `errorlog`, `monolog` (any Monolog handler), `custom` (a `via` factory class), `papertrail`, `stderr`, `null`, `emergency`. `Log::extend('name', fn ($app, $config) => Logger)`.

**(d) Config.** `config/logging.php`: `'default' => env('LOG_CHANNEL','stack')`, `'channels' => ['stack' => ['driver'=>'stack','channels'=>['single']], 'daily' => [...], 'slack' => [...]]`; per-channel `level`, `tap` (formatters), `bubble`.

**(e) Layered.** `Log::channel('x')`, `Log::stack([...])`, `Log::build([...])` on-demand, **shared context** (`Log::withContext([...])`) and the **Context** facade (2.24), exception reporting hooks, deprecation log channel, `Laravel Pail` (installed; tail logs).

**(f) Dynamism.** Built on Monolog (handlers/formatters are PHP classes configured by name); `tap` classes by string.

**(g) Transfer.** Keep: named channels with stack composition, structured context, per-channel level, on-demand channel. Change: use the host ecosystem's structured logging (SLF4J/MDC, `tracing`, pino) behind a thin typed `Logger` contract; context as typed key/values with propagation (see 2.24).

---

## 2.22 Localisation

**(a)** Translation strings by key with placeholders and pluralisation, per-locale files, fallback locale, per-request locale.

**(b) Contract (verified).**

```php
interface Translator {
    public function get($key, array $replace = [], $locale = null);
    public function choice($key, $number, array $replace = [], $locale = null);
    public function getLocale();  public function setLocale($locale);
}
interface Loader { public function load($locale, $group, $namespace = null);  public function addNamespace($namespace, $hint);  public function addJsonPath($path); }
interface HasLocalePreference { public function preferredLocale(); }
```

**(c) Drivers.** One file-based loader (PHP arrays in `lang/{locale}/{group}.php` and JSON). Namespaces for packages (`loadTranslationsFrom`). Custom: bind a different `Loader`.

**(d) Config.** `app.locale`, `app.fallback_locale`, `app.faker_locale`.

**(e) Layered.** Pluralisation syntax (`{0} none|{1} one|[2,*] many`), `__()` and `trans_choice()` helpers, `HasLocalePreference` consumed by mail and notifications, `Lang::get`, publishing of package lang files, validation messages.

**(f) Dynamism.** Dotted string keys into nested arrays; string-embedded plural rules; helpers.

**(g) Transfer.** Keep: `Translator`/`Loader` split, locale preference propagated to mail/notifications, locale as part of request context. Change: typed message keys where feasible (generated constants) and ICU MessageFormat for plural/gender instead of Laravel's own pipe syntax; the host language's i18n libraries are better than a port.

---

## 2.23 Console / Artisan

**(a)** A command-line framework on Symfony Console: application commands, framework commands (`migrate`, `queue:work`, `schedule:run`, `route:list`, `config:cache`, `make:*`), interactive prompts and a REPL (`tinker`, installed).

**(b) Contract.** `Contracts\Console\Kernel` (`bootstrap`, `handle`, `call`, `queue`, `all`, `output`, `terminate`), `Isolatable` (marker: only one instance at a time via a lock), `PromptsForMissingInput` (marker). Commands extend `Console\Command` (Symfony `Command`).

**(c) Extension.** Commands registered by providers (`$this->commands([...])`), auto-loaded from `app/Console/Commands`, or as closures in `routes/console.php` (`Artisan::command('x', fn () => ...)`). v13 attributes (verified `Console/Attributes/`): `#[Signature]`, `#[Description]`, `#[Aliases]`, `#[Hidden]`, `#[Help]`, `#[Usage]`. A **signature string** declares args and options: `'mail:send {user} {--queue=}'`.

**(d) Config.** None.

**(e) Layered.**
- **Generators / scaffolding** (verified in `Foundation/Console/`): `make:controller`, `model`, `migration`, `job`, `event`, `listener`, `mail`, `notification`, `policy`, `request`, `resource`, `rule`, `provider`, `middleware`, `command`, `test`, `cast`, `scope`, `observer`, `enum`, `interface`, `trait`, `class`, `component`, `exception`, `channel`, `view`, `config`. Backed by **stub files** (`Foundation/Console/stubs`) that the app can publish and customise (`stub:publish`), via `GeneratorCommand` (substitutes namespace and class name into a template).
- **Prompts** (`laravel/prompts`, installed, verified file list): `text`, `password`, `confirm`, `select`, `multiselect`, `suggest`, `search`, `pause`, `table`, `spin`, `progress`, forms, notes; with validation and non-interactive fallback.
- Operational commands: `optimize`/`optimize:clear`, `down`/`up`, `about`, `env`, `key:generate`, `vendor:publish`, `storage:link`, `event:cache`, `route:cache`, `config:cache`, `package:discover`, `env:encrypt`.
- Command isolation (`Isolatable`), scheduling integration, signal handling (`Console/Signals.php`), `Artisan::call()` from code and tests (`artisan('x')->expectsQuestion(...)`), `queue` a command.

**(f) Dynamism.** Signature string parsed at runtime (`Console/Parser.php`); commands discovered by directory scanning and reflection; `handle()` method-injected; generator uses string templates.

**(g) Transfer.** Keep: a single operational CLI as the framework's entry for migrations, queue workers, scheduler, cache clear, route list, with **generators from published stubs** (this is high-value for a team: consistent module scaffolding) and prompts. Change: typed command definitions (annotations/derive macros building arguments from a struct), generators emit typed source files in the host language; make the CLI introspect the real registered module graph (providers, routes, jobs). Drop: signature strings.

---

## 2.24 Context

**(a)** Request-scoped (and propagated) key/value metadata, automatically attached to log lines and carried into queued jobs, so correlation ids and tenant/user ids flow end to end. New in recent versions.

**(b) Contract (verified).** `Log\Context\Repository` (concrete) with methods: `add`, `addIf`, `get`, `has`, `forget`, `pull`, `only`, `except`, `all`, `push`/`pop` (stacks), `increment`, `remember`, `scope(callable $callback, array $data = [], array $hidden = [])`, **hidden** variants (`addHidden`, `getHidden`, not logged), and propagation hooks `dehydrating(callable)` / `hydrated(callable)` plus `dehydrate()` / `hydrate($context)`. `Contracts\Log\ContextLogProcessor` is the Monolog processor that adds context to log records.

**(c) Drivers.** None.

**(d) Config.** None; registered by `ContextServiceProvider` as a base provider.

**(e) Layered.** Context is **dehydrated into the job payload** when a job is dispatched and hydrated in the worker; Log lines automatically include it; hidden context is carried but not logged; `Context::scope` for nested overrides; `#[Context]` container attribute.

**(f) Dynamism.** Facade plus global per-process state, which needs flushing between Octane requests; values stored as untyped data, serialised in job payloads.

**(g) Transfer.** Keep: this idea whole. A typed `RequestContext` (correlation id, tenant, principal, trace id) propagated through logging, outbound HTTP, queue messages (as message headers/metadata), and events is essential for multi-service work. Change: use the host's context propagation (Java `ScopedValue`, Rust task-local / `tracing` span, TS `AsyncLocalStorage`) and align with W3C trace context; "hidden" vs "visible" fields as explicit sensitivity flags.

---

## 2.25 Concurrency and process

**Concurrency (`Concurrency/`, verified).** `Concurrency::run([...closures])` runs closures in parallel and returns results; `defer([...])` runs after the response.

```php
interface Driver {
    public function run(Closure|array $tasks, CarbonInterval|int|null $timeout = null): array;
    public function defer(Closure|array $tasks): DeferredCallback;
}
```
Drivers: `process` (spawns child `php artisan` processes and serialises closures), `fork` (`spatie/fork`, pcntl), `sync` (verified `ConcurrencyManager` extends `MultipleInstanceManager`). This exists because PHP has no shared-memory threads; it is **closure serialisation to child processes**.

**Process (`Process/`, verified).** Fluent wrapper on Symfony Process: `Process::run('cmd')`, `->timeout()`, `->env()`, `->path()`, `->start()` async with `InvokedProcess`, `pool`, `pipe`, `->throw()`, `ProcessResult` (`successful`, `exitCode`, `output`, `errorOutput`, `seeInOutput`), `Process::fake()` with sequences, `preventStrayProcesses`, `assertRan`.

**Transfer.** Concurrency: drop entirely. Java 25 (virtual threads, structured concurrency) and Rust (tokio) have native concurrency. Process: keep only as a thin typed wrapper with a fake; low priority.

---

## 2.26 Feature flags (Pennant)

*Package `laravel/pennant`; source not installed. From docs 13.x.*

**(a)** Define features, check them per **scope** (usually a user, a team, or any serialisable identity), store resolved values so a feature is stable per scope, roll out progressively.

**(b) Contract (docs).**

```php
namespace Laravel\Pennant\Contracts;
interface Driver {
    public function define(string $feature, callable $resolver): void;
    public function defined(): array;
    public function getAll(array $features): array;
    public function get(string $feature, mixed $scope): mixed;
    public function set(string $feature, mixed $scope, mixed $value): void;
    public function setForAllScopes(string $feature, mixed $value): void;
    public function delete(string $feature, mixed $scope): void;
    public function purge(array|null $features): void;
}
interface DefinesFeaturesExternally { public function definedFeaturesForScope(mixed $scope): array; }   // wrapping a SaaS flag service
interface FeatureScopeable { public function toFeatureIdentifier(string $driver): mixed; }
```

**(c) Drivers.** `array` (in-memory), `database` (stores resolved values per feature+scope). Custom: `Feature::extend('redis', fn ($app) => new RedisFeatureDriver(...))`.

**(d) Config.** `config/pennant.php`: default store, stores.

**(e) Layered.** Class-based features, rich (non-boolean) values, `Feature::for($team)->active('x')`, `HasFeatures` trait, Blade `@feature`, `EnsureFeaturesAreActive` middleware, eager loading, bulk activate/deactivate, purge, in-memory cache per request, events, `Feature::define('x', fn ($user) => ...)` closures resolving the initial value lazily and persisting it.

**(f) Dynamism.** Features keyed by name or class name; scope serialisation by `FeatureScopeable` or model identity (Eloquent morph); closures as resolvers.

**(g) Transfer.** Keep: the scope-based `Driver` contract (get/set/delete/purge per `(feature, scope)`), sticky resolved values, `DefinesFeaturesExternally`. Change: features as typed constants/objects; scope as a typed `FeatureScope` (tenant/user/environment). A small, self-contained module worth porting; also a natural hook for multi-tenant plan gating.

---

## 2.27 Observability: Telescope, Pulse, Nightwatch

- **Telescope** (docs): a debug assistant recording requests, exceptions, logs, queries, jobs, mail, notifications, cache ops, schedule runs, events, gate checks, outgoing HTTP, model changes, Redis commands, views, dumps. **Watchers** subscribe to framework events; entries are stored in database tables (`telescope_entries`); dashboard at `/telescope`, gated by `viewTelescope`; designed primarily for local/staging; filter closures decide what to keep in production; `telescope:prune`. This works only because framework subsystems emit **events** consistently (cache events, query events, mail events, ...).
- **Pulse** (docs): production-oriented aggregate metrics: slow requests/jobs/queries/outgoing requests, queue throughput, exceptions, cache hit rate, per-user usage, server CPU/memory (via the `pulse:check` daemon). **Recorders** listen to framework events and write **pre-aggregated buckets** (`count`, `sum`, `min`, `max`, `avg`) with optional sampling and a Redis ingest path; custom cards are Livewire components and custom recorders are classes with a `$listen` property; `viewPulse` gate; MySQL/MariaDB/PostgreSQL storage only.
- **Nightwatch** (docs): a **hosted** monitoring product with a local always-on agent installed via `laravel/nightwatch`; data goes to Laravel's backend using an environment token. Not self-hostable per the page read.

**Transfer.** The lesson is architectural: instrument by emitting typed events/spans at every capability seam (cache, queue, db, http, mail) so any recorder can attach. In a typed framework use OpenTelemetry spans/metrics at those seams instead of building a bespoke store. Pulse's pre-aggregated buckets and sampling are a good design for *in-product* usage metrics (relevant to the metering gap).

---

## 2.28 Performance runtime: Octane

*Installed (`laravel/octane`), source directory listed, config read; internals not read.* Runs the application inside a long-lived server (Swoole, RoadRunner, FrankenPHP): boots once, handles many requests, resets selected state per request (`octane.php` listeners: `FlushTemporaryContainerInstances`, `DisconnectFromDatabases`, `CollectGarbage`, `FlushOnce`, ...). Adds `Octane::concurrently()`, `Octane::table()` (Swoole shared tables), an `octane` cache driver, `tick`/`task` APIs, and worker recycling (`max_requests`).

**Transfer.** Notable only as evidence: because Laravel's design assumes one request per process, every long-running runtime must catalogue and flush leaked state (static caches, facade instances, singletons holding request data, `scoped` bindings). A JVM/Rust framework starts long-lived, so **request scope must be explicit in the container** (singleton vs request-scoped is a first-class lifecycle, not a patch).

---

## 2.29 Search (Scout)

*Package; not installed; docs TOC and intro read.* `laravel/scout` adds full-text search to models via a `Searchable` trait and `toSearchableArray()`. Engines (docs TOC): Algolia, Meilisearch, Typesense, Turbopuffer, plus `database` and `collection` engines (no external service). Index updates are queueable; `Model::search('x')->paginate()`; custom engines by extending `Laravel\Scout\Engines\Engine` (docs section "Custom Engines"; class name not verified). v13 adds semantic/vector search (query builder `whereVectorSimilarTo`, `Str::toEmbeddings`, AI SDK).

**Transfer.** A `SearchIndex<T>` driver contract (upsert/delete/search) with an in-memory engine for tests; bind to aggregates by explicit projection, not model traits.

---

## 2.30 Payments (Cashier)

*Not verified in source or docs.* One line: Cashier wraps Stripe (and Paddle in a separate package) subscription billing with model traits (`Billable`). Heavily Eloquent-bound; skip.

---

## 2.31 Auth packages: Sanctum, Passport, Socialite, Fortify

| Package | What it does | Verified |
|---|---|---|
| **Sanctum** | Two features in one guard named `sanctum`: (1) **personal access tokens** stored hashed (SHA-256) in `personal_access_tokens`, sent as `Authorization: Bearer`, with **abilities** (`createToken($name, ['server:update'])`, `tokenCan`, middleware `abilities:`/`ability:`), optional expiry, `sanctum:prune-expired`; (2) **SPA cookie authentication**: same-top-level-domain SPA uses the session cookie with CSRF (`/sanctum/csrf-cookie`, `XSRF-TOKEN` → `X-XSRF-TOKEN`), `statefulApi()` middleware, CORS with credentials. The guard tries the cookie first, then the bearer token. | docs |
| **Passport** | Full OAuth2 server (authorisation code + PKCE, client credentials, personal tokens, scopes) | not verified |
| **Socialite** | OAuth login with third-party identity providers (GitHub, Google, ...), community providers | not verified |
| **Fortify** | Headless authentication back end: routes/controllers for login, registration, password reset, email verification, password confirmation, **TOTP two-factor** (`Features::twoFactorAuthentication([...])`), customised through action classes (`CreateNewUser`, `ResetUserPassword`), rate-limited with a named `login` limiter. All starter kits (React, Svelte, Vue, Livewire) use it. | docs (starter-kits page) |
| **WorkOS AuthKit** | Starter-kit variant delegating login to WorkOS (social, passkeys, magic auth, SSO) | docs (starter-kits page) |

Starter kits also ship **Teams** (users belong to teams with a current team and team-slug routes; docs) which is a lightweight tenancy convention, not framework-level multi-tenancy.

**Transfer.** Sanctum's *token with abilities, hashed at rest, shown once* and *cookie-first then bearer* guard chain are directly reusable ideas. Passport-class OAuth servers should be a separate product rather than framework core.

---

## 2.32 Front-end bridges: Blade, Livewire, Inertia, Vite, Precognition

### Blade (verified in source: `View/`)

Server-side template language compiled to cached PHP (`View/Compilers/BladeCompiler.php`). `@if`, `@foreach`, `{{ $x }}` (escaped), `{!! !!}` raw, layouts via `@extends/@section` or **components** (`<x-alert type="error"/>` anonymous or class-based with slots), `@include`, `@stack/@push`, directives (`Blade::directive`), view composers/creators, shared view data. Contracts: `View\Factory` (`make`, `share`, `composer`, `exists`), `View\View`, `View\Engine` (`get($path, $data)`). Engines: Blade and PHP. Dependent on PHP code inside templates (full language available in templates).

### Livewire

*Not installed; docs mention Livewire 4 in the Livewire starter kit; internals not verified.* Server-rendered reactive components: component classes hold public properties synced with the browser via AJAX round-trips (with Alpine on the client), Blade views render fragments. Pulse dashboards use it. Heavily dependent on PHP reflection and magic (public property hydration/dehydration, `wire:` directives). Not a transfer candidate.

### Inertia (protocol verified at inertiajs.com/the-protocol)

Inertia is an adapter pattern: the server controller returns "a page" (component name + props) instead of HTML or an API payload; the client (React/Vue/Svelte, starter kits use **Inertia 3**) renders it as an SPA without a separate API. The **protocol**:

**Page object** (JSON). Required: `component` (string), `props` (object, always containing `errors`), `url` (string), `version` (asset version string). Optional (protocol page lists): `encryptHistory`, `clearHistory`, `preserveFragment`, `mergeProps`, `prependProps`, `deepMergeProps`, `matchPropsOn`, `deferredProps` (grouped), `rescuedProps`, `onceProps` (map with optional `expiresAt`), `scrollProps`, `sharedProps` (list of top-level keys that came from shared data), `flash`.

**Two response modes** for the same URL:

1. First visit (no `X-Inertia` header): a normal **HTML** document containing a root element carrying the page object as JSON (`<div id="app" data-page="...">`; in the Laravel adapter via the `<x-inertia::app />` component and `resources/views/app.blade.php`), plus the asset tags.
2. Subsequent navigation (request header `X-Inertia: true`): the response is **JSON** of the page object with response headers `X-Inertia: true` and `Vary: X-Inertia` (so caches key on it).

**Request headers.**

| Header | Meaning |
|---|---|
| `X-Inertia: true` | Inertia XHR visit |
| `X-Inertia-Version` | The asset version the client currently holds |
| `X-Requested-With: XMLHttpRequest` | Also sent |
| `X-Inertia-Partial-Component` | Component being partially reloaded (partial data honoured only if it matches the response component) |
| `X-Inertia-Partial-Data` | Comma-separated prop keys to include (**only**) |
| `X-Inertia-Partial-Except` | Comma-separated prop keys to exclude |
| `X-Inertia-Reset` | Prop paths whose merge behaviour should reset |
| `X-Inertia-Error-Bag` | Name of the validation error bag to scope `errors` |
| `X-Inertia-Except-Once-Props` | Once-prop keys the client already holds |
| `X-Inertia-Infinite-Scroll-Merge-Intent` | append / prepend for infinite scroll |
| `Purpose: prefetch` | Prefetch request |

**Partial reloads.** Full visit resolves regular, "always", merge, once and scroll props; optional and deferred props are announced but not resolved. Partial reload resolves only requested props via only/except; "always" props (like `errors`) ignore the filters. Props can be lazily evaluated closures so skipped props cost nothing. **Deferred props** are announced in `deferredProps` (by group) and loaded by an automatic follow-up partial reload.

**Asset versioning.** The server puts a `version` in the page object (in the Laravel adapter, `HandleInertiaRequests::version()` returns it, typically a hash of the Vite manifest). On a GET, if `X-Inertia-Version` differs from the server's, the server replies **409 Conflict** with `X-Inertia-Location: <full url>` and the client does a full page load to fetch new assets. Non-GET requests never get the version-mismatch 409. Flash data is re-flashed on that 409.

**Redirects.** After `POST/PUT/PATCH/DELETE`, redirects must be **303 See Other** (or 302 converted) so the follow-up is a GET. External redirects use **409 + `X-Inertia-Location`**. Fragment redirects use 409 + `X-Inertia-Redirect`.

**Validation.** On failure the server redirects back with errors in the session; the next page object contains `props.errors` (field → message or list of messages), scoped by `X-Inertia-Error-Bag` if provided. (Precognition below uses 422.)

**Shared props.** Data included in every response, defined in one place: in the Laravel adapter, `HandleInertiaRequests::share(Request $request)` middleware method. Typical content: authenticated user summary, flash messages, app name, permissions. `sharedProps` in the page object lists the shared keys.

**Laravel server side.** `Inertia::render('Orders/Show', ['order' => $props])`, root view `app.blade.php`, the `HandleInertiaRequests` middleware (extends the adapter's base middleware, registered in `bootstrap/app.php`; defines `version()` and `share()`), lazy/deferred/merge/once prop wrappers, SSR via a separate Node process (`build:ssr`).

**Hooks a framework needs to offer for Inertia (derived from the above):**
1. A request flag "is Inertia" (header test), "partial" (headers), version header.
2. A response type `Page{component, props, url, version, ...}` renderable as JSON or as HTML-with-embedded-JSON, plus `Vary: X-Inertia`.
3. A **version provider** callback and middleware that returns 409 + `X-Inertia-Location` on GET mismatch.
4. A redirect rewriter: non-GET Inertia request that gets 302 → 303; external location → 409 + header.
5. A **shared props provider** executed per request (lazy closures allowed).
6. Lazy prop wrappers with partial-reload filtering (only/except/always) and deferred/once/merge metadata.
7. Validation-error and flash integration (errors always present; error bags).
8. Optional SSR gateway.

### Vite integration (docs read; page is large)

`laravel-vite-plugin` (Node side) + `@vite([...entrypoints])` Blade directive (PHP side). In dev the plugin writes a `public/hot` file containing the dev-server URL; the directive detects it and emits script tags pointing at the dev server with HMR. In production the directive reads `public/build/manifest.json` to emit hashed asset URLs with preload/CSS links; optional asset prefetching, CSP nonce (`Vite::useCspNonce()`), SRI, custom base URLs, Inertia page resolution helpers, and an easy way to disable Vite in tests. The Inertia asset `version` is commonly the manifest hash.

**Hook needed:** a manifest-reading asset resolver (dev "hot" file vs production manifest), tag emitters with nonce support, and a version string.

### Precognition (docs read; source for the middleware verified)

"Anticipate the outcome of a future request": the client sends the real request with header `Precognition: true` (and optionally `Precognition-Validate-Only: field1,field2`). `HandlePrecognitiveRequests` middleware (verified in `Foundation/Http/Middleware/`) rebinds the controller dispatchers to precognition variants that **run all middleware and resolve the controller's dependencies (so FormRequests validate) but skip the controller body**. Response: `204 No Content` for success, `422` with errors, plus response header `Precognition: true` (verified: `$response->headers->set('Precognition', 'true')`). `$request->isPrecognitive()` lets rules and middleware vary (for example skip a slow "uncompromised password" check, skip file validation unless asked). Client libraries `laravel-precognition-vue/-react/-alpine` provide debounced live validation; Inertia 2.3+ has it built in.

**Transfer.** Precognition is a neat protocol that rides on the "validation happens before the handler body" structure. In a typed framework: input DTO validation must run as a separable step before handler invocation so a "dry-run" mode can stop after it.

---

## 2.33 Other first-party pieces worth knowing

| Item | Note | Status |
|---|---|---|
| **Pipeline** (`Illuminate\Pipeline`) | Generic `send()->through()->then()` used for HTTP middleware, Bus pipes | verified |
| **Pagination** (`Contracts/Pagination`) | Offset (`LengthAwarePaginator`, `Paginator`) and `CursorPaginator` | verified |
| **Redis** (`Redis/`, `RedisManager`) | Named connections, cluster, `phpredis`/`predis`, pub/sub, `funnel`/`throttle` limiters | verified |
| **Collections** (`Illuminate\Collections`) | Fluent array/lazy collection API, used throughout; PHP-specific convenience | verified (dir) |
| **Macroable / Conditionable / Reflection** | Runtime method addition by name (`Macroable` stores closures, `__call`), a dynamism hotspot | verified (dir) |
| **JsonSchema** (`Contracts/JsonSchema`) | v13: schema builder used by the AI SDK/MCP tooling | verified (dir) |
| **Horizon** | Redis-queue dashboard and supervisor | not verified |
| **Reverb** | First-party WebSocket server | not verified |
| **Dusk** | Browser testing | not verified |
| **Sail** | Docker dev environment | not verified |
| **Pint** | Code style (installed, php-cs-fixer based) | verified (dir) |
| **Pail** | Log tailing in the terminal (installed) | verified (dir) |
| **Tinker** | REPL (installed) | verified (dir) |
| **AI SDK, MCP, Boost** | v13 headline: provider-agnostic text, agents, embeddings, images, audio | release notes only |
| **Cloud / Forge / Vapor / Nova** | Hosted products | not verified |
| **Pest/PHPUnit** | Test runners (PHPUnit 12 in example) | verified (composer.json) |

---

# Part 3. Cross-cutting observations

## 3.1 Capability table

Legend: **Contract** = a real interface exists in `Illuminate\Contracts` or the package. **Driver** = pluggable backend selected from config. **Fake** = first-party test double. **Reflection** = needs runtime reflection or string-name dispatch for normal operation. **Global** = relies on facades/static/global process state in typical use.

| Capability | Contract? | Driver-based? | Test fake? | Needs runtime reflection? | Global state? |
|---|---|---|---|---|---|
| Container | Yes (`Container`) | No | `instance`/`mock` | **Yes**: constructor/method reflection, attributes | Static `Container::getInstance` |
| Service providers | Base class, `DeferrableProvider` | No | n/a | Yes: `boot` method injection, manifest by class name | Static publish registries |
| Config | Yes (`Config\Repository`) | No (file only) | Set at runtime | No | Global `config()` |
| Routing/middleware | Partial (`Registrar`; middleware is convention) | No | `withoutMiddleware`, in-process HTTP tests | **Yes**: controller/param reflection, string aliases | `Route` facade |
| Request/validation | Yes (`Validator`, `ValidationRule`) | No | n/a | Yes: rule strings, FormRequest resolution | Facade `Validator` |
| Responses/resources | Partial (`Responsable`) | No | n/a | Partly: `__get` delegation | No |
| Authentication | Yes (`Guard`, `UserProvider`) | **Yes** (guards, providers) | `actingAs` | Yes: model-bound principal | `Auth` facade |
| Authorisation | Yes (`Gate`) | No | n/a | **Yes**: policy name guessing | `Gate` facade |
| Sessions | Yes (`Session`) | **Yes** | array driver | No | `session()` |
| Cache | Yes (`Store`, `Repository`, `Factory`) | **Yes** | `array` driver (no recording fake) | Method-name driver creation | `Cache` facade |
| Locks | Yes (`Lock`, `LockProvider`) | Yes (via cache store) | `array` lock | No | via facade |
| Rate limiting | **No** (concrete class) | Via cache store | No | Closures by name | `RateLimiter` facade |
| Queues/jobs | Yes (`Queue`, `Job`, `Factory`) | **Yes** | `Queue::fake`, `Bus::fake` | **Yes**: `serialize`, class names, closure serialisation | `Queue`/`Bus` facades |
| Events | Yes (`Dispatcher`) | No | `Event::fake` | Yes: listener discovery, `Class@method` | `Event` facade |
| Broadcasting | Yes (`Broadcaster`, `Factory`) | **Yes** | `Event::fake` / `Broadcast::fake`-like (not verified) | Yes: public-property payload | Facade |
| Scheduling | **No** (concrete) | No (lock store only) | No first-party fake | Closures; signature parsing | `Schedule` facade |
| Mail | Yes (`Mailer`, `Mailable`, `Factory`) | **Yes** (transports) | `Mail::fake` | Yes: duck-typed recipients, Blade | `Mail` facade |
| Notifications | Dispatcher/Factory only; **channel has no interface** | Yes (channels) | `Notification::fake` | **Yes**: `to<Channel>` naming | `Notification` facade |
| Filesystem | Yes (`Filesystem`, `Cloud`, `Factory`) | **Yes** | `Storage::fake` (real local) | No | `Storage` facade |
| Database layer | Partial (`ConnectionInterface`) | **Yes** (connectors) | Test traits (real DB) | `__call`, dynamic wheres | `DB` facade |
| Eloquent | No clean contract | n/a | Factories (real DB) | **Heavy** | Heavy (static boot, dispatcher) |
| Encryption / hashing | Yes | Hashing yes; encrypter by cipher | n/a | No | Facades |
| HTTP client | No (concrete) | No | `Http::fake` | No | `Http` facade |
| Logging | PSR-3 | **Yes** (channels) | Log channel `null`/array | Monolog by class | `Log` facade |
| Localisation | Yes | Loader | n/a | No | `__()` |
| Console | Kernel contract; commands are classes | No | `artisan()` test helpers | Yes: signature strings, discovery | `Artisan` facade |
| Context | Contract for processor only | No | n/a | No | **Yes** |
| Concurrency | Yes (`Driver`) | **Yes** | `sync` driver | Closure serialisation | Facade |
| Process | Yes (`ProcessResult`) | No | `Process::fake` | No | Facade |
| Feature flags | Yes (`Driver`) | **Yes** | array driver | Scope serialisation | Facade |
| Octane | n/a | Server drivers | n/a | n/a | Exists to manage global state |
| Search (Scout) | Engine abstract class | **Yes** | collection engine | Model traits | Facade |
| Telescope/Pulse | n/a | storage | n/a | Event listening | Facades |
| Blade/Inertia/Vite | `View` contracts | Engines | `withoutVite`, Inertia assertions | Blade compiles PHP | Facades |

## 3.2 The ten ideas most worth carrying over (ranked)

1. **Named, config-selected driver instances behind one contract, with composition by name** (`default` + `stores/disks/mailers/connections`, plus `failover`/`roundrobin`/`scoped`). It is the single biggest productivity feature: swap Redis for in-memory in tests, S3 for local, SES for Postmark, with no application code change.
2. **The two-phase service-provider lifecycle (register then boot) as the unit of modularity**, with deferred providers and package auto-discovery. In a typed framework it becomes an explicit module with declared provides/requires and startup validation, which removes the ordering hazards Laravel hides.
3. **Contract-first capabilities with a first-party recording fake that implements the same contract** (`MailFake`, `QueueFake`, `EventFake`). Gives domain code fast tests without mocking frameworks.
4. **Strict separation of Store/driver contract from user-facing Repository/facade over it** (cache `Store` vs `Repository`; mail transport vs `Mailer`; queue `Queue` vs `Job`). Driver authors implement the small contract; users get the rich API.
5. **Pipeline/middleware as the one composition mechanism** across HTTP, bus commands, and job handling (`handle(passable, next)`), including terminate hooks.
6. **Request context propagation** (correlation id, tenant, principal) automatically into logs, jobs, and outbound calls (Context).
7. **Job and notification behaviour declared on the type** (tries, backoff, timeout, unique key, queue, channels) with job middleware (rate limit, no-overlap, throttle exceptions) and chains/batches with a persistent batch store.
8. **Guard/user-provider split for authentication** plus `Gate`/policy with before/after hooks and `inspect` returning a reason.
9. **After-commit dispatch** (events/jobs/mail wait for the surrounding transaction to commit) and nested-transaction savepoints, which shows the framework acknowledging the dual-write problem and is the natural seam for an outbox.
10. **Generators from published stubs plus a single operational CLI** (scaffolding consistent modules; `route:list`, `event:list`, `schedule:list`, `config:show`-style introspection).

Runner-up: the Inertia protocol hooks (2.32), if the owner wants first-class SPA delivery; Precognition (dry-run validation); scoped disks; owner-token locks.

## 3.3 What should NOT be carried over

| Item | Reason |
|---|---|
| **Active record / Eloquent** | Persistence, behaviour, serialisation and querying in one class; hidden queries and N+1; attributes untyped; ties jobs, routes, auth and broadcasting to the row type; hard to test without a DB. Replace with aggregates + repositories (3.5). |
| **Facades** and global helpers (`app()`, `config()`, `auth()`) | Service locator through a static; hides dependencies; requires `swap` + Mockery for tests; exist because PHP has no cheap DI at call sites. Constructor injection covers it. |
| **Real-time facades**, **Macroable**, `__call`/`__get` proxies | Runtime method creation; undiscoverable by tools and compilers. |
| **Serialising objects/closures onto queues** (`serialize(clone $job)`, `SerializesModels`, `laravel/serializable-closure`) | Language-specific wire format keyed by class name; version-fragile; object-injection risk; models re-fetched with different semantics; unusable across languages (a multi-runtime goal makes this disqualifying). Use versioned schema messages with string types. |
| **String-typed DSLs**: validation rule strings (`'required\|max:255'`), middleware strings (`throttle:60,1`), command signatures, route action strings (`Class@method`), policy name guessing, `create<Driver>Driver` method-name convention, `to<Channel>` convention | Errors surface at run time; no refactoring support; need parsers. |
| **Free-form config arrays read lazily** | No schema; missing keys become `null`; errors on first use rather than boot. Use typed config objects validated at startup. |
| **Untyped contracts** (`$key`, `$value`, mixed returns) | The contracts are only types in docblocks. |
| **Container auto-wiring by runtime reflection** | Valuable ergonomics but runtime-only failure; use compile-time wiring (annotation processing, derive macros, generated modules) or explicit module registration. |
| **Per-process mutable singletons / "flush state between requests"** (Octane workaround list) | Make lifecycle (singleton / scoped / transient) explicit. |
| **`env()` and config caching dance** | A product of per-request boot cost. |
| **Provider ordering by registration sequence, last-binding-wins** | Implicit; declare dependencies and detect conflicts. |
| **Locks with TTL-only safety and 250 ms polling** | Add fencing tokens and notification-based waiting. |
| **`false`-on-error returns** in filesystem (`throw` option) | Use typed results. |
| **`Authenticatable` as an Eloquent model** | Principals should be value objects. |
| **Blade/Livewire in the core** | Templating on a typed back end is a separate decision; Inertia-style JSON pages are easier to support. |
| **Concurrency via child processes and closure serialisation** | Host languages have real concurrency. |
| **Silent fixed-window rate limit as the only algorithm** | Offer token bucket/sliding window through a store interface. |

## 3.4 Gaps: what Laravel core does not provide (new design work)

Each item was checked by reading the relevant parts of the installed source (and, for packages, the docs) and by grepping the whole `Illuminate` tree.

### (1) Config from remote or database sources, encrypted values, AWS Parameter Store / Secrets Manager

| Aspect | Exists? |
|---|---|
| Config from files + env | Yes (`LoadConfiguration`, phpdotenv) |
| Config cache | Yes (`config:cache`) |
| Encrypted `.env` file (`env:encrypt`/`env:decrypt`) | **Yes, whole-file only**, uses the app key, for committing secrets per environment |
| Per-value encrypted config values | **No** (the `encrypted` cast is for model attributes, not config) |
| Database-backed or remote config repository | **No** (`Config\Repository` is in-memory arrays) |
| AWS Parameter Store / Secrets Manager / Vault / Key Vault | **No** (grep found nothing; AWS drivers read key/secret/region from config or the SDK default chain) |
| Runtime refresh / change notification | **No** |
| Typed/validated config schema | **No** |

Third-party packages exist in the ecosystem for SSM/Secrets Manager (not verified). New design: a `ConfigSource` contract with layering (defaults < files < env < remote), typed config binding with validation, secret references (`${secret:aws-sm:path}`) resolved lazily with caching and rotation callbacks, redaction in logs/dumps.

### (2) Usage metering and quotas for outbound mail or messages per application or group (hourly/daily/monthly)

**Does not exist in core.** Grep for `quota` finds only an unrelated minified JS file. What exists: `RateLimiter` + `Limit::perMinute/perHour/perDay` (fixed-window counters in cache with TTL, keyed by arbitrary string, so "N per day per key" is expressible), mail events (`MessageSending`, `MessageSent`), and the `RateLimited` queue middleware. What is missing: a persistent usage ledger, monthly/billing-period windows aligned to calendar boundaries, per-tenant/application/group quota definitions held as data, pre-send quota check with reservation, overage policies (block/queue/charge), usage query API, alerts. Counters in cache are lossy on eviction/failover. New design work: a `UsageMeter` (record `(subject, metric, amount, at)` durable, aggregate by window), a `QuotaPolicy` (subject → limits per window), an enforcement hook at the outbound-message seam (before `Transport.send`), reservation + settle semantics (to handle failures), and pre-aggregated buckets (Pulse's recorder design is a useful reference, docs).

### (3) A unified outbound messaging abstraction covering email, SMS and WhatsApp with delivery tracking

**Partially, by composition, not unified.** Mail (Symfony Mailer transports) and Notifications (channels: mail, database, broadcast in core; Vonage SMS and Slack as separate packages) exist; community channels for other services exist (laravel-notification-channels.com, docs). **WhatsApp is not provided** by core or by a first-party package (grep: no `whatsapp`, `twilio`; Vonage appears only as a package name in docs). Missing even for email/SMS: a **message record** (id, channel, recipient, status), **delivery status** state machine (queued, sent, delivered, bounced, failed), provider **webhook ingestion** for delivery receipts, retry/failover policy across providers per channel (mail has `failover`/`roundrobin`; notifications do not), templates per channel with approval metadata (WhatsApp templates), opt-out/consent, idempotency keys. `SentMessage` returned by the mailer carries the Symfony message and is not persisted. New design work: `Message` aggregate + `MessageChannel` driver contract (`send -> ProviderReceipt`) + `DeliveryStatus` event stream + webhook endpoints per provider + a store. Notifications' `via()/to<Channel>()` model is a reasonable *front door* but needs the above underneath.

### (4) A domain layer: aggregates, repositories, unit of work, domain events with an outbox

**Not provided.** Grep for `UnitOfWork`, `aggregate root`, `outbox` in the framework: none. What Laravel offers instead: Eloquent models (active record) as the persistence/domain object; `DB::transaction()` as a manual unit of work; model events/observers as in-process "domain events"; the `Dispatcher` for application events; `ShouldDispatchAfterCommit` / `afterCommit` queue option as a partial answer to the dual-write problem (the job is held in memory until commit; **if the process dies after commit and before enqueue the message is lost**, which a transactional outbox prevents). No repository abstraction (people write their own classes wrapping Eloquent, or use packages), no aggregate/identity-map, no optimistic-concurrency convention (no version columns built in), no outbox table or relay. The `database` queue driver does allow "enqueue in the same transaction as business data" when the queue connection is the same database (a de facto outbox, since the jobs table is written inside the transaction); this works only for Laravel workers and PHP-serialised payloads (see 2.10). New design work: `Repository<Aggregate>` contract, `UnitOfWork` (transaction scope + tracked aggregates + collected domain events), versioned aggregates (optimistic locking), domain event collection on aggregates, **transactional outbox** written in the same transaction plus a relay (poll/CDC) publishing to queues/bus with idempotent consumers, and an inbox for dedup.

### (5) Multi-tenancy

**Not provided in core** (grep for `tenant` finds nothing in `Illuminate`). Closest first-party items: starter-kit **Teams** (docs: users belong to teams with a current team slug in URLs and URL defaults; application-level convention), `scoped` filesystem disks (prefix per tenant), global scopes on Eloquent (the common DIY approach: a `tenant_id` global scope), `Context`, route model scoped bindings, `Pennant` scopes, and `scoped` container bindings. Popular community packages (Spatie multitenancy, Tenancy for Laravel) are not verified here. Missing: tenant resolution (domain/subdomain/header/token), tenant-aware config/connections/caches/queues/storage switching, tenant-scoped jobs (context must carry tenant into the worker), data isolation strategies (row-level, schema-per-tenant, database-per-tenant), migrations across tenants, per-tenant secrets and quotas. New design work: a `TenantContext` in the request context, a `TenantResolver` pipeline stage, tenant-scoped driver instances (cache prefix, disk prefix, DB connection/schema/RLS `SET`), tenant in every queue message header, and test helpers.

### (6) Delegating user management to an external identity platform

**Partially.** The `Guard`/`UserProvider` split makes it *possible* (custom `Auth::extend` guard that validates a JWT/OIDC token; custom provider that maps claims to a principal). First-party options: **Socialite** (OAuth login, not verified in detail), **Passport** (an OAuth *server*, the opposite direction), **Sanctum** (own tokens), **WorkOS AuthKit starter-kit variant** (docs: social, passkeys, magic auth, SSO, with Laravel keeping a local user record and session). Missing in core: an OIDC relying-party/resource-server implementation (discovery, JWKS caching, token validation, refresh), SCIM/user-sync, role/permission mapping from claims (the example app uses `spatie/laravel-permission` and the `flowcatalyst/laravel-sdk` for this, i.e. third-party), logout propagation/back-channel logout, service-account (client-credentials) principals. Laravel still expects a **local `users` row** in nearly all flows (`Authenticatable` is an Eloquent model). New design work: `IdentityProvider` contract (verify token → `Principal` with typed claims, roles, tenant), JWKS cache, optional local shadow-user store *as an option*, service principals as first-class.

## 3.5 Implications for a domain-model-first framework (summary of what replaces Eloquent)

Not in Laravel; included because it follows from what is dropped. Persistence concerns become: **aggregate** (behaviour + invariants, no framework base class) ← **repository** interface (domain-owned) ← **repository implementation** in an infrastructure module using the query/transaction layer (2.17); **unit of work** scopes the transaction, tracks loaded aggregates, collects domain events and writes the outbox rows; the **queue/notification/mail** capabilities consume integration events from the outbox. This keeps the parts of Laravel that are good (contracts, drivers, providers, queues, mail) while making the domain layer framework-independent.

## 3.6 Commonly said about Laravel (neutral summary; not sourced citations)

**Commonly praised.**
- Developer productivity: a coherent, batteries-included set of capabilities with consistent naming and one way to configure each (`default` + named stores).
- Excellent documentation and a large ecosystem of first-party packages and community packages.
- Easy testing of side effects through fakes (`Mail::fake()`, `Queue::fake()`).
- Fast onboarding: scaffolding, starter kits, conventions that new team members recognise across projects.
- Pragmatic queue/scheduler/mail/storage abstractions that let small teams ship quickly.
- Frequent releases with a low-friction upgrade policy (minor/patch non-breaking; majors annual).

**Commonly criticised (by people building large, domain-heavy systems).**
- **Active record** couples domain logic to the schema; models become god objects or anemic bags; business rules end up spread across models, observers, jobs and controllers; hard to unit test without a database.
- **Magic**: facades, `__call`/`__get` proxies, macros, container auto-resolution and string-based conventions make code hard to navigate with static analysis, and hide dependencies and control flow (observers, global scopes, event listeners firing implicitly).
- **Weak typing in the framework's own API surface**: untyped contracts, arrays as configuration and data transfer, strings as identifiers; correctness depends on tests and extra static-analysis tooling.
- **Hidden queries / N+1** by default through lazy relation loading.
- **Queue payload fragility** from PHP object serialisation and class-name coupling; closures on queues.
- **Per-request bootstrap cost and global state** requiring workarounds for long-running servers (Octane).
- **Convention over configuration** can work against explicit architecture (hexagonal/DDD) because the framework's grain pulls toward "fat controllers/models"; teams impose their own layers on top.
- **Modularity**: monolith-centred; packages integrate through global providers and config; strong module boundaries need extra discipline.
- Some first-party features are hosted/commercial products (monitoring, deployment), which some teams weigh when choosing.

---

# Appendix: key source locations (v13.17.0)

All under `laravel-flowcatalyst-example/vendor/laravel/framework/src/Illuminate/`.

| Topic | File(s) |
|---|---|
| Manager base | `Support/Manager.php`, `Support/MultipleInstanceManager.php` |
| Specialised managers | `Cache/CacheManager.php`, `Filesystem/FilesystemManager.php`, `Mail/MailManager.php`, `Queue/QueueManager.php`, `Database/DatabaseManager.php`, `Broadcasting/BroadcastManager.php`, `Auth/AuthManager.php`, `Log/LogManager.php`, `Session/SessionManager.php`, `Hashing/HashManager.php`, `Notifications/ChannelManager.php`, `Concurrency/ConcurrencyManager.php`, `Redis/RedisManager.php` |
| Provider base and boot | `Support/ServiceProvider.php`, `Foundation/Application.php` (register/boot/deferred), `Foundation/ProviderRepository.php`, `Foundation/PackageManifest.php`, `Support/DefaultProviders.php` |
| Container | `Container/Container.php`, `Container/BoundMethod.php`, `Container/Attributes/*` |
| Kernels and bootstrappers | `Foundation/Http/Kernel.php`, `Foundation/Console/Kernel.php`, `Foundation/Bootstrap/*`, `Foundation/Configuration/ApplicationBuilder.php` |
| Pipeline | `Pipeline/Pipeline.php` |
| Router | `Routing/Router.php`, `Routing/Attributes/Controllers/*` |
| Facades and fakes | `Support/Facades/Facade.php`, `Support/Testing/Fakes/*` |
| Locks | `Cache/Lock.php`, `Cache/RedisLock.php`, `Cache/DatabaseLock.php`, `Cache/LuaScripts.php`, `Cache/Limiters/ConcurrencyLimiter.php` |
| Rate limiter | `Cache/RateLimiter.php`, `Cache/RateLimiting/Limit.php` |
| Queue payload | `Queue/Queue.php` (`createObjectPayload`), `Queue/SerializesModels.php`, `Queue/CallQueuedClosure.php`, `Queue/Middleware/*`, `Queue/Attributes/*` |
| Context | `Log/Context/Repository.php` |
| Contracts | `Contracts/**` (170-odd interfaces) |
| Example app config/bootstrap | `laravel-flowcatalyst-example/config/*.php`, `bootstrap/app.php`, `bootstrap/providers.php` |
