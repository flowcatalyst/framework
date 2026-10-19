# Contract: per-client settings (M5, first slice)

Status: written 2026-10-18 with the first build (owner ruling 2026-10-18: "M5 first slice only:
per-client settings (F6)"). Language-neutral where it says so; Java names where the shape matters.
Cache, storage, metering, messaging and the platform config driver are **not** part of this slice
(section 9).

Builds on: [core](core.md) (`ClientId`, `Context`), [write path](write-path.md) (aggregates,
repositories, use cases), [config and code](config-and-code.md) (the rule this follows).

## 1. What a per-client setting is

A value that differs from one client to the next, that someone with the right permission changes
while the application runs, and that the application reads when it works for that client: a
ticket service's SLA minutes, an invoice number prefix, a retention period. Tickets built one by
hand in the trial (a table, a repository, SQL, an aggregate, a use case, an event, an operation and
a page for four integers; friction F6); this contract is the part every application would write the
same way.

It is **not** configuration. Configuration holds the settings of a service or a connection, varies
per deployment and is read at start-up ([config and code](config-and-code.md) section 1). A
per-client setting varies per client within one deployment, lives in the database, changes at run
time and is data.

## 2. The rule it follows: definitions are code, values are data

*Could an application want several of these, with different values?* Yes: an application has many
settings, each with its own type, default and rule. So **each setting is declared in code**, as a
typed value, where the application defines it; there is no list of settings in YAML and no
registry file. The **values** are rows in the application's database, one set per client.

```java
record SlaMinutes(int low, int normal, int high, int urgent) {}

static final ClientSetting<SlaMinutes> SLA =
    ClientSetting.of("tickets.sla", SlaMinutes.class, new SlaMinutes(2880, 1440, 480, 60))
        .rule(v -> v.urgent() <= v.high() ? Optional.empty() : Optional.of("..."));
```

`ClientSetting<T>`:

| Part | Rule |
|---|---|
| key | `[a-z][a-z0-9._-]{0,99}`; unique within the application (two definitions with one key are the application's bug; the store cannot tell, the values would collide) |
| type | a Java class Jackson can read and write as JSON: a record, an enum, a string, a number, a list. The stored JSON is the type's JSON |
| default | required, never null; what a client that never set the value gets |
| rule | optional `Optional<String> check(T)`; the message is safe to show a person. Run on every write; also callable alone for a use case's `validate` phase |

## 3. Storage

Table `fw_client_settings`, created by the module's migration set (module id `client-settings`,
the default database):

| Column | |
|---|---|
| `client_id` | `TEXT PRIMARY KEY`; the client's code |
| `version` | `BIGINT NOT NULL`; the aggregate's version (optimistic locking, write-path section 2) |
| `settings` | `JSONB NOT NULL`; an object from key to the value's JSON |

There is one row per client, and the row is one aggregate, `ClientSettings` (id `ClientSettingsId`
= the client's code): the version covers all of the client's settings. **Trade-off, chosen:** two
people changing two different settings of one client at the same moment conflict, and the loser
retries under the use case's conflict policy; in exchange a use case that changes several settings
changes them atomically, and the table has no per-key rows to page. A client with no row has every
default. There is no `updated_at` or `updated_by`: when and by whom is the audit row of the use case
that wrote it (time from the injected `InstantSource`, principal from the `Context`).

The row is language-neutral: another language's framework reads and writes the same table and the
same JSON objects.

## 4. Reading

`ClientSettingsStore.get(client, setting)` returns the stored value decoded as the setting's type, or
the default when the client has no row or no value for the key. It reads straight from the database,
**without a cache** (section 9); an application that reads a setting on a hot path caches it itself
until the cache capability exists. A stored value that no longer decodes as the setting's type is
`IllegalStateException` naming the key and the client: a defect found where it happens, never silently
replaced by the default. `ClientSettings.get(setting)` is the same read on a loaded aggregate (a use
case that has the aggregate in hand).

## 5. Writing

The framework supplies **no use case, no operation and no admin page** for writing. Who may change
which setting, what event it emits and what the form looks like are the application's. What it
supplies is the aggregate and its repository, so the write is an ordinary use case with an ordinary
plan:

```java
ClientSettings settings = repository.forClient(db, context.client());      // loaded, or an empty version-0 one
ClientSettings changed = settings.with(SLA, new SlaMinutes(...));          // runs the rule
return Plan.save(changed, repository, new SlaPolicyChanged(...));          // insert at version 0, else update
```

- `ClientSettingsRepository.forClient(db, client)` returns the client's row, or a new aggregate at
  version 0 with no values; `Plan.save` inserts it. Two first writes at once: the second fails with
  `UNIQUE_VIOLATION` on the primary key (write-path section 4), which the use case's conflict policy
  does not retry; the application's handler for that answer is to try again.
- `with(setting, value)` runs the setting's rule and throws `IllegalArgumentException` with the
  message when it fails. A use case checks first (`ClientSetting.check(value)` in its `validate`
  phase) so that a refusal is a `VALIDATION` error; the throw is the backstop.
- `without(setting)` removes a client's value, so the default applies again.
- `update` and `delete` follow the repository contract (write-path section 2): version-checked, row count
  returned. The client is the aggregate's client, so the runner's cross-client check applies.

## 6. Wiring (Java)

Module `framework-client-settings`, package `io.flowcatalyst.framework.settings`:
`ClientSettingsModule` (list it after the database module; it contributes the migration set and
provides `ClientSettingsRepository` and `ClientSettingsStore`), `ClientSetting<T>`, `ClientSettings`,
`ClientSettingsId`, `ClientSettingsRepository`, `ClientSettingsStore`. Depends on `framework-core`,
`framework-database` and `framework-usecase`.

## 7. Rulings taken (confirmed by the owner 2026-10-19)

The defaults below, as implemented in Java and in Go, are confirmed by the owner (2026-10-19).

1. **Definitions in code, values in the database** (section 2). Alternative: a settings list in
   configuration with a default per key. Not chosen: the owner's rule puts many-instance things in
   code, and a typed value carries a rule and a type that YAML cannot.
2. **One row per client, one version for all of a client's settings** (section 3). Alternative: a row
   per `(client, key)`. Chosen against: it loses atomic multi-setting changes and costs a read per key.
3. **No cache, no framework use case or endpoint in this slice** (sections 4, 5, 9).
4. **A value that does not decode is an error, not the default** (section 4), by the owner's principle
   of never skipping silently.
5. **Keys are the application's namespace** (`tickets.sla`): the framework does not prefix them. The
   application code is a sensible first segment.

## 8. Tests the build must have

Against PostgreSQL: a read of a client with no row gives the default; a written value is read back,
typed, by the store and by the aggregate; another client is unaffected (isolation); the rule refuses
a bad value on `with` and is callable alone; `without` restores the default; a value that no longer
decodes is `IllegalStateException` naming the key; optimistic locking (two writers on one row: the
loser sees `STALE_AGGREGATE`); two first writes at once (`UNIQUE_VIOLATION`); a use case run by the
runner saves settings, event and audit row in one transaction; a rolled-back run writes nothing; key
validation; the stored JSON shape (`{"key": value}`). Mutation checks: drop the `client_id` filter in
the store (isolation test fails); drop the version check in `update` (locking test fails).

## 9. Not in this slice

A cache (and its invalidation across replicas), file storage, metering and outbound message tracking
(the rest of M5), the platform config driver (platform-managed per-client configuration), an
admin page and a generic settings endpoint, per-setting permissions, change history, a settings
registry that lists every setting an application defines (the admin and docs pages that want one
declare the list themselves), encrypted values (secrets are `SecretResolver`'s, per-client secrets
contract).

## 10. Conformance vectors (2026-10-18)

`conformance/settings/client-settings.json` (key rules; 16 scripts of 86 operations: defaults, writes and
versions, `without`, reads that do not decode, the rule messages, the stored JSONB per value type, stale and
first-write conflicts, the cross-client refusal) and `conformance/migrations/client-settings.json` (the history
row), written by `ClientSettingsConformanceTest`. The migration file is the shared source of the table; it also has
`DEFAULT '{}'` and a `CHECK (jsonb_typeof(settings) = 'object')` that section 3 does not list. Recorded Java behaviour
the contract does not state: a stored JSON `null` for a key gives the default through the store but `null` from
`ClientSettings.get`; Jackson coerces a string `"12"` to an integer setting and `12.0` to `12`; the key order of the
text Java sends is unspecified (the table prints `jsonb` in its own order, which is what the vector compares). The
trial's `POLICY_NOT_ORDERED` and `MINUTES_INVALID` are the application's codes: the module carries only the rule's
message (`IllegalArgumentException`: `client setting <key>: <message>`).
