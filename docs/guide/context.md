<!-- types: Context, ClientId, PrincipalId, ApplicationCode, CorrelationId, ContextResolver -->
# Context

`Context` is a value that says **who is acting, for which client, as part of which request**. You
pass it explicitly to use cases, lock keys and handlers. The framework never stores it in a static
or a thread-local, so there is no ambient context to forget about.

```java
public record Context(
    ClientId client,
    @Nullable PrincipalId principal,
    @Nullable ApplicationCode application,
    CorrelationId correlationId,
    @Nullable String causationId,
    @Nullable String executionId) { ... }
```

| Field | Required | Meaning |
|---|---|---|
| `client` | always | the tenant the work is for, as a `ClientId` that holds the platform's **client code** (see below). Every aggregate carries one, and a use case refuses to write another client's aggregate |
| `principal` | no | the user or service account acting; the audit row and event metadata record it |
| `application` | no | the application code |
| `correlationId` | always | follows a request into logs, events and jobs; generated when absent |
| `causationId` | no | the event or message that caused this work |
| `executionId` | no | the job or run this work belongs to |

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
`withExecutionId`. `findPrincipal()` and `findApplication()` return `Optional`s.

In a **test or a command**, build the context as above. In an **HTTP request** the context comes
from a `ContextResolver`, a service your application provides:

```java
app.provide(
    ContextResolver.class,
    lookup -> request -> Context.of(new ClientId("demo")).withPrincipal(new PrincipalId("usr_demo")));
```

> **No real resolver exists yet.** The framework ships none: login, sessions and token validation
> are milestone M4c (see [STATUS](STATUS.md)). The snippet above is a placeholder that treats every
> request as one fixed user. A real application must write its own resolver (for example from a
> bearer token) and throw `HttpException(401, "UNAUTHENTICATED", ...)` when it cannot resolve.
> See [HTTP](http.md#context-and-authentication).

After the resolver returns, the framework replaces the context's correlation id with the
request's `X-Correlation-Id` (or the one it generated), so logs, events and the audit row agree.

## What uses it

- **Use cases** take a `Context` on every run: `useCases.run(useCase, context, command)`. The
  client scopes every read and write, the principal and ids go into event metadata and the audit
  row. See [The write path](write-path.md).
- **Locks** can be client-scoped: `LockKey.client(clientId, "invoice-run")`. See [Locks](locks.md).
- **Outbox rows** use the `ClientId` value as the payload's `clientCode` (the client code, above).

Specification: [contracts/core.md](../contracts/core.md) section 3.
