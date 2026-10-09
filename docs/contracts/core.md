# Contract: core (modules, instances, context, config)

Status: M1. Java names are given where the shape matters; the rules are language-neutral.

## 1. Modules and the application lifecycle

A **module** has an id (lowercase, `[a-z][a-z0-9-]*`, unique in the application) and four
hooks:

| Hook | May do | May not do |
|---|---|---|
| `register(AppBuilder)` | declare settings, provide services, register drivers, contribute migrations | build services, read config values, start threads |
| `boot(App)` | read config, look up services, add health checks, start background work | provide new services |
| `started(App)` | work that needs every module booted: compile a route table, start accepting traffic | provide new services |
| `shutdown(App)` | stop background work it started | — |

`App.builder().module(a).module(b)...start()`:

1. Calls every module's `register`, in the order listed.
2. Loads and validates config (section 4). Any problem fails here.
3. Builds every provided service eagerly. A provider is a function from a lookup to a value;
   when it asks for another service, that one is built first. The resulting build order is
   recorded.
4. Calls every module's `boot`, in the order listed.
5. Calls every module's `started`, in the order listed, after **every** `boot` has completed.
   Use it for work that depends on what other modules did in `boot`, so that module order does
   not matter for it. If any `boot` failed, no `started` runs.
6. Returns the running `App`.

Failures, all thrown from `start()` as `StartupException`, collected where possible so one start
reports every problem, each naming its module and key:

- two modules with one id; two providers for one type;
- a lookup of a type nobody provides (names the module whose provider asked);
- a cycle between providers (names the cycle);
- any config error (section 4);
- an unknown driver name or a driver factory failure (section 2);
- an exception from a provider, a `boot` hook or a `started` hook.

If `start()` fails after building some services, it closes those it built, in reverse order,
before throwing.

**Lookup** is `get(Class<T>)`: a typed read from a map built in step 3, throwing when nobody
provides the type. `find(Class<T>)` is the same read returning `Optional<T>` (no throw), for
optional collaborators. No reflection. Only one
provider per type; named variants are reached through their capability's registry (section 2).

**Shutdown** (`App.close()`, idempotent, also run from a JVM shutdown hook the builder may
install): module `shutdown` hooks in reverse module order (for every module whose `boot` ran, which
includes any whose `started` ran or failed), then every built service that is
`AutoCloseable` in reverse build order. Each step's failure is logged and does not stop the
rest. Setting `app.shutdown.drain` (duration, default `10s`) is the time each hook may take
before it is logged as overrun and the next one runs.

**Health:** `App.health()` holds named checks added at boot. `check()` runs them all, each with
a timeout (`app.health.timeout`, default `2s`), and returns a result per check: `Up`, `Down(reason)`
or `TimedOut`.

## 2. Named instances and drivers

A capability with drivers (locks now; cache, storage, mail later) works the same way:

```yaml
locks:
  default: main
  instances:
    main:   { driver: database }
    shared: { driver: redis, connection: default, prefix: "fw:lock:" }
    local:  { driver: memory }
```

- Modules register drivers by name for a capability: `drivers.register(LockStore.class,
  "redis", factory)`. A second registration of one name for one capability fails start-up.
- At start-up every configured instance is built from its section. An instance naming an
  unregistered driver fails start-up, listing the registered names.
- The factory receives the instance name, its `InstanceConfig` section and the service lookup.
  It reads its keys through typed getters. After the factory returns, any key in the section it
  did not read (other than `driver`) fails start-up as unknown: a typo cannot pass silently.
- A capability with only one driver in practice may declare a default driver name; its instances
  may then omit `driver` (database instances default to `postgresql`, Redis instances to
  `redis`). Capabilities with a real choice (locks, cache) have no default driver.
- The factory also receives the instances of the same capability already built, so wrapper
  drivers can refer to them.
- `default` names the instance returned by the capability's `defaultInstance()`. If there is
  exactly one instance, `default` may be omitted. If there are several and it is omitted, or it
  names a missing instance, start-up fails.
- The capability registry (`Locks`) is itself a provided service: `get(name)` returns the
  instance or throws `IllegalArgumentException` naming the known instances.
- A capability with no configured instances is valid; its registry is empty and `get` throws.
- Wrapper drivers (failover, prefixed, metered) are out of scope for M1, but the factory's
  access to the registry of already-built instances must allow them later.

## 3. Context

`Context` is a value passed explicitly to use cases and handlers. It is never stored in a static
or a thread-local by the framework.

| Field | Type | Required |
|---|---|---|
| client | `ClientId` | always (design rule 8) |
| principal | `PrincipalId` | no |
| application | `ApplicationCode` | no |
| correlation id | `CorrelationId` | always; generated when absent |
| causation id | text | no (added in M2a: the event or message that caused this work) |
| execution id | text | no (added in M2a: the job or run this work belongs to) |

Id types are records wrapping a validated non-blank string. `ClientId` matches
`[A-Za-z0-9._-]{1,100}`: it never contains `:`, so it can be embedded in composite keys (locks,
cache) without two clients producing the same key. `ApplicationCode` matches `[a-z][a-z0-9-]*`. M1 provides the types; building a `Context` from a request is M3.

## 4. Config

### Declarations

Every setting is declared once, in code, by the module that reads it, during `register`:

| Part | Values |
|---|---|
| key | dotted lowercase, `[a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)*` |
| type | string, integer, long, boolean, duration, URI, enum, string list |
| kind | value or secret |
| default | a value, or none |
| required | yes or no; a required setting with no value after all sources fails start-up |
| description | text, for tooling |

A key declared twice fails start-up. Instance sections (section 2) are the one exception to
"declared in code": their keys are read by the driver factory and checked for unknown keys
after it runs.

### Sources, lowest to highest

1. The declaration's default.
2. `config/application.yaml`, then `config/application-{env}.yaml`. Missing files are fine.
3. Environment variables.
4. External stores: none in M1 (AWS and the platform are M5).

`env` is the environment variable `APP_ENV`, default `local`. The config directory is
`config/` under the working directory unless the builder is given another path.

**Environment variable names:** the key uppercased with `.` and `-` replaced by `_`, so
`database.instances.main.url` is `DATABASE_INSTANCES_MAIN_URL`. Only names derived from
declared keys and from instance sections present in the files are looked up; the environment
is never scanned. Instances cannot be created from environment variables alone.

**Values:** durations accept `500ms`, `30s`, `5m`, `2h`, `1d` and ISO-8601 (`PT30S`). Booleans
accept `true`/`false` only. A value that does not parse fails start-up with the key, the source
it came from and the expected type. The raw text of a secret is never included in an error.

### Secrets

- `Secret` is its own type. `toString()` returns `Secret[****]`; it is not serialisable; the
  raw value is read with `reveal()`.
- A secret declaration never has a real default.
- When `env` is `local` or `test`, `config/secrets.local.yaml` is read as a source between the
  files and the environment. In any other environment that file is ignored. The project
  template lists it in `.gitignore`.

### Reading

After start-up, `Config` returns typed values for declared settings (`config.get(setting)`) and
sections for instance configuration. Values do not change while running in M1.

### Names and where a setting can live (ruling 2026-10-09)

Keys are one global namespace; the framework adds no module prefix (a duplicate key fails
start-up naming both modules). A module id need not appear in its keys.

Every key has a **component** (its first segment) and a **path** (the rest, any number of dotted
parts): `mail.host` is component `mail`, path `host`; `mail.smtp.host` is component `mail`, path
`smtp.host`. The name is the same everywhere a setting can live; only the address around it
differs:

| Store | Address of `mail.smtp.host` in application `amazing-app` |
|---|---|
| default, files, environment | `mail.smtp.host` (env `MAIL_SMTP_HOST`) |
| FlowCatalyst platform (M5) | client (tenant-scoped settings only) / `amazing-app:mail` / `smtp.host` |

- The application code comes from `flowcatalyst.application` and is never written in a key. On the
  platform it is the scope of the setting (access is checked on application and client, plan
  section 6 of `framework-plumbing.md`); `{application}:{component}` has the same shape as an
  event type's prefix.
- A setting stored on the platform needs at least two segments. A one-segment key is allowed for
  local sources only; declaring it with a platform store fails start-up.
- The framework's own components cannot be stored on the platform: `app`, `http`, `database`,
  `redis`, `locks`, `flowcatalyst`. They are deployment settings (env or files). Declaring a
  platform-stored setting under one of them fails start-up.
- Scope: a **service**-scoped setting has one value for the application; a **tenant**-scoped
  setting has one value per client. Local sources have no client dimension, so a tenant-scoped
  setting read locally needs an explicit client to resolve against.
- A declaration may name an explicit platform component and path when the default mapping does
  not suit. The local name is unchanged.
- Two different keys can map to one environment variable (`mail.host-name`, `mail.host.name`);
  start-up must fail naming both when it happens. (Check whether the M1 loader already does; the
  platform driver in M5 must.)

### Errors

`StartupException` lists every config problem found, each with key and source: unparseable
value, missing required setting, unknown key in an instance section, unknown driver, duplicate
declaration.

## 5. Commands (added with M4a)

A module may claim a command name in `register` with `app.command(Command)`; `Command` is a name
and `run(App, List<String> args, PrintStream out)` returning an exit code. `AppBuilder.launch(args)`
looks at the first argument: a claimed name runs the command after start-up step 3 (services built,
no `boot`) and closes the application, returning `Launch.Ran(exitCode)`; anything else starts
normally and returns `Launch.Started(app)`. Two modules claiming one name fail start-up. `start()`
is unchanged for callers that do not use commands.
