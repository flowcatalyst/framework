<!-- types: PerClient, Context, ClientId, PrincipalId, PrincipalKind, ApplicationCode, CorrelationId, ContextResolver -->
# Context

`Context` is a value that says **who is acting, for which client, as part of which request**. You
pass it explicitly to use cases, lock keys and handlers. The framework never stores it in a static
or a thread-local, so there is no ambient context to forget about.

```java
public record Context(
    ClientId client,
    @Nullable PrincipalId principal,
    @Nullable PrincipalKind principalKind,
    @Nullable ApplicationCode application,
    CorrelationId correlationId,
    @Nullable String causationId,
    @Nullable String executionId,
    Set<String> permissions) { ... }
```

| Field | Required | Meaning |
|---|---|---|
| `client` | always | the tenant the work is for, as a `ClientId` that holds the platform's **client code** (see below). Every aggregate carries one, and a use case refuses to write another client's aggregate |
| `principal` | no | the user or service account acting; the audit row and event metadata record it |
| `principalKind` | no; only with a principal | `USER` (a person) or `SERVICE` (a service account): the platform's `type` claim. Absent when unknown |
| `application` | no | the application code |
| `correlationId` | always | follows a request into logs, events and jobs; generated when absent |
| `causationId` | no | the event or message that caused this work |
| `executionId` | no | the job or run this work belongs to |
| `permissions` | no (empty) | what the principal may do, in the platform's form (`ingestor:ingestion:manage`); check with `hasPermission` (below) |

## The id types

Each is a record wrapping a validated string, so a mix-up is a compile error rather than a bug.

| Type | Rule |
|---|---|
| `ClientId` | `[A-Za-z0-9._-]{1,100}`. It never contains `:`, so it can be embedded in composite keys (lock keys, cache keys) without two clients colliding |
| `PrincipalId` | non-blank |
| `ApplicationCode` | `[a-z][a-z0-9-]*` |
| `CorrelationId` | non-blank; `CorrelationId.generate()` makes a random UUID |

### What goes in a ClientId

A `ClientId` is a string wrapper with a name that suggests the platform's internal client id. It is
not. The platform has two names for a client: the generated id (`clt_0HZ...`) and the **client code**
(also called its identifier: a short slug such as `acme`, matching `[a-z0-9][a-z0-9-]*`). The
framework uses the **code** everywhere:

- the dispatch receiver puts the code from `X-FlowCatalyst-Client: {id}:{code}` into the handler's
  `Context` (`DispatchDelivery.client()` is an `Optional` of both halves, `id()` and `code()`);
- the [outbox](write-path.md#6-the-outbox) writes the `ClientId` value as the payload's
  `clientCode` (and in the row's `client_id` column), which is how the platform learns whose event
  it is.

Three things get called "client id" in this framework and the platform. Which one you hold decides
where it may go:

| What | Looks like | Where the framework uses it |
|---|---|---|
| The client's **code** (identifier) | `acme`: `[a-z0-9][a-z0-9-]*`, never contains `_` | `new ClientId("acme")`, so `Context.client()`; the outbox's `clientCode` and `client_id` column; `LockKey.client(...)`; `ClientSelector` results; `DispatchDelivery.client().code()`; the `clients` token entry's part after `:`; `X-FlowCatalyst-Client`'s part after `:` |
| The client's **platform id** | `clt_0HZ...` (at most 17 characters) | `DispatchDelivery.client().id()`; the part before `:` in `clients` and in `X-FlowCatalyst-Client`; the session's `homeClient`; the dispatch-jobs batch's `clientId` (set from `platformClientId`, never derived from the `Context`); `AccessToken.mayActFor(...)` accepts either |
| An **OAuth client id** (credentials) | whatever the platform issued for the application | `FlowCatalystClient`/`PlatformClient` authenticate the application's service account with it (`clientCredentials(clientId, secret)`); it names an application, not a tenant |

Rules of thumb. A field of your own API or table that says which tenant a record belongs to should
be named for what it holds (`clientCode`, or `clientId` only if it is the `clt_` id) and should be
the same kind as `Context.client()`, so a filter and a `Context` compare equal; the wire name is
yours, the framework does not read it. If the page has to show or accept the other kind, translate
at the edge: the `clients` entry of the principal pairs the two (`clt_1:acme`).

So when you build a `Context` yourself, for example in a `ContextResolver`, put the client **code**
in `new ClientId(...)`. Putting a `clt_...` id there writes events the platform cannot attribute to
the client you meant. Nothing in the type or the framework can tell the two apart, because both are
valid strings. The class itself is only validated for shape: `[A-Za-z0-9._-]{1,100}`.

## Building one

```java
Context context = Context.of(new ClientId("acme")).withPrincipal(new PrincipalId("usr_1"));
```

`Context.of(client)` generates a correlation id and leaves the rest empty. The `with...` methods
return a copy: `withPrincipal`, `withApplication`, `withCorrelationId`, `withCausationId`,
`withExecutionId`, `withPermissions`. `findPrincipal()`, `findPrincipalKind()` and
`findApplication()` return `Optional`s. The seven-, six- and four-argument constructors still exist:
they leave the principal kind unknown, and the six- and four-argument ones give an empty permission
set.

### The principal kind

```java
Context service =
    Context.of(new ClientId("acme")).withPrincipal(new PrincipalId("prn_0HZXEQ5Y8JY5Z"), PrincipalKind.SERVICE);
service.principalKind(); // SERVICE
```

A kind is never present without a principal (the constructor refuses it), and `withPrincipal(id)`
without a kind **clears** any kind the context had, so a kind never describes another principal.
The framework sets it wherever it knows it: `AuthModule`'s resolver from the session or the bearer
token's `type` (`SERVICE`, else `USER`), and the [receivers](receivers.md#who-runs-the-handler)
always `SERVICE`, the application's own service account. Check it in `authorize` when an operation
is for people only, or for the service only. It is not written to the outbox (the platform has no
field for it).

### Permissions

`hasPermission(required)` is true when a held permission **matches**: the same number of
`:`-separated segments, and each held segment is `*` or equal to the required one. That is the
platform's rule, so a permission the platform grants (`billing:*:view`) means the same here.

```java
Context c = Context.of(new ClientId("acme")).withPermissions(Set.of("billing:*:view"));
c.hasPermission("billing:invoice:view");      // true
c.hasPermission("billing:invoice:edit");      // false
c.hasPermission("billing:invoice:line:view"); // false: a wildcard is one segment, never more
```

A wildcard in the *required* permission grants nothing, and a blank one never matches. Use it in a
use case's `authorize` step, which sees only the `Context` and the command (a rule on the stored record is `authorizeLoaded`, see the write path).

In a **test or a command**, build the context as above. In an **HTTP request** the context comes
from a `ContextResolver`, a service your application provides:

```java
app.provide(
    ContextResolver.class,
    lookup -> request -> Context.of(new ClientId("demo")).withPrincipal(new PrincipalId("usr_demo")));
```

> The snippet above is a placeholder that treats every request as one fixed user; use it in tests.
> The real resolver is `AuthModule`'s ([Sessions and login](sessions-and-login.md)): it fills the
> principal, the client (an identifier, as above) and the permissions from the session cookie or a
> platform bearer token, and answers `401 UNAUTHENTICATED` when it cannot.

After the resolver returns, the framework replaces the context's correlation id with the
request's `X-Correlation-Id` (or the one it generated), so logs, events and the audit row agree.

## Work for one client after another

A scheduled job's context is the platform's (`_platform`) and a background loop has none, but the work
goes through use cases, which are scoped to one client. `context.forClient(clientId)` copies the context
for another client and keeps the principal, its kind, the application, the correlation, causation and
execution ids and the permissions. `PerClient.each(base, clients, task)` runs the task for each client with
that client's context; one client's exception is recorded for that client and the others still run, and it
stops starting clients when the thread is interrupted:

```java
// SlaSweep: the clients come from one deliberate, unscoped query that returns client codes only
List<ClientId> clients = TicketQueries.overdueClients(database, clock.instant());
Map<ClientId, PerClient.Outcome<int[]>> outcomes = PerClient.each(ctx, clients, this::sweepClient);
// sweepClient(Context forClient): scoped queries, then useCases.run(flagSlaBreach, forClient, command)
```

`forClient` does not check that the principal may act for the client; the use case's `authorize` does,
as for any caller. The platform does not fire a job once per client: the application does the fan-out,
so where the clients come from is yours (never from the platform's client ids; build a `ClientId` from the
code). A loop does the same inside its tick, under the loop's lock gate.

## What uses it

- **Use cases** take a `Context` on every run: `useCases.run(useCase, context, command)`. The
  client scopes every read and write, the principal and ids go into event metadata and the audit
  row. See [The write path](write-path.md).
- **Locks** can be client-scoped: `LockKey.client(clientId, "invoice-run")`. See [Locks](locks.md).
- **Outbox rows** use the `ClientId` value as the payload's `clientCode` (the client code, above).

Specification: [contracts/core.md](../contracts/core.md) section 3.
