<!-- types: ClientSetting, ClientSettings, ClientSettingsId, ClientSettingsRepository, ClientSettingsStore, ClientSettingsModule, PerClient -->
# Per-client settings

A value that differs per client, that someone changes while the application runs and that the
application reads when it works for that client: a ticket service's SLA minutes, an invoice prefix, a
retention period. The framework keeps these in one table, `fw_client_settings`, and lets you **define
each setting in code**; reading and writing are the write path you already use.

This is not configuration. Configuration holds the settings of a service or a connection and varies
per deployment ([Config](config.md)); a per-client setting varies per client within one deployment and
lives in the database. The specification is [contracts/client-settings.md](../contracts/client-settings.md).

## Define a setting

A setting is a constant: a key, the type of its value (anything Jackson reads and writes as JSON), the
default a client gets until it sets one, and an optional rule. From the tickets example
(`TicketSettings`):

```java
public record SlaMinutes(int low, int normal, int high, int urgent) { ... }

public static final ClientSetting<SlaMinutes> SLA =
    ClientSetting.of("tickets.sla", SlaMinutes.class, DEFAULT_SLA).rule(m -> problem(m).map(Problem::message));
```

The key matches `[a-z][a-z0-9._-]{0,99}` and is yours to namespace (`tickets.sla`). The rule returns a
message that is safe to show a person, or empty when the value is fine; the default must satisfy it.
Two definitions with the same key would share a value, so keep each key in one place.

## Wire it

List `ClientSettingsModule` after the database module. It adds the migration (table
`fw_client_settings`) and provides `ClientSettingsRepository` and `ClientSettingsStore`:

```java
.module(new DatabaseModule())
.module(new OutboxModule())
.module(new UseCaseModule())
.module(new ClientSettingsModule())   // TicketsCore
```

Maven: `framework-client-settings` (in the BOM).

## Read

```java
SlaMinutes sla = store.get(context.client(), TicketSettings.SLA);        // the client's, or the default
int minutes = settings.forClient(database, client).get(TicketSettings.SLA).minutesFor(priority);
```

`ClientSettingsStore.get` reads the database on every call: there is **no cache** yet, so cache a hot
read yourself. A client with no row, or no value for the key, gets the default. A stored value that no
longer decodes as the setting's type is an `IllegalStateException` that names the key and the client; it
is never replaced by the default silently. Extra fields in a stored JSON object are ignored, so a value
type can gain fields.

## Write: an ordinary use case

The framework supplies the aggregate and its repository, not a use case or an endpoint: who may change
which setting, what event it raises and what the form looks like are yours.
`ClientSettingsRepository.forClient(db, client)` returns the client's row, or an empty aggregate at version
0 that `Plan.save` inserts. From `TicketUseCases`:

```java
.validate((context, c) -> TicketSettings.problem(minutes(c)).map(p -> UseCaseError.validation(p.code(), p.message())))
.authorize(requires(Permissions.SLA_MANAGE))
.execute((context, c) -> {
  ClientSettings saved = settings.forClient(database, context.client()).with(TicketSettings.SLA, minutes(c));
  return Plan.save(saved, settings, new SlaPolicyChanged(events.metadata(context, TicketEvents.POLICY_CHANGED, saved.id()), ...));
})
```

- `with(setting, value)` runs the rule and throws `IllegalArgumentException` if it fails; check first
  (`setting.check(value)`, or your own `validate`) so that a person gets a `VALIDATION` error.
- `without(setting)` removes the client's value and the default applies again.
- The settings, the event and the audit row commit in one transaction; a failed run writes nothing.

**One row per client.** All of a client's settings are one aggregate with one version. Two people changing
two settings of the same client at the same moment conflict and the loser retries (give the use case a
retry policy if that matters); in exchange a use case can change several settings atomically. Two first
writes for a client at once: the second is `UNIQUE_VIOLATION` and the caller tries again.

## What it does not do

No cache, no framework endpoint, admin page or per-setting permission (the application writes those), no
change history beyond the audit rows of your use cases, no list of every setting (declare the list where a
page needs one), no encrypted values (use [per-client secrets](secrets.md)), and not the platform's
per-client configuration (the platform config driver is not built). Metering, messaging, storage and
caching, the rest of M5, are not built either.

## Tests

`ClientSettingsTest` (framework-client-settings, 14 tests on PostgreSQL): defaults, typed read-back,
client isolation, the rule, `without`, an undecodable value, schema evolution, key rules, optimistic
locking, two first writes, the cross-client refusal, one transaction, rollback, `find` scoping. The tickets
example's `TicketUseCasesTest`, `TicketsApiTest` and browser suite exercise the same through its SLA page.
Mutation-checked: dropping the `client_id` filter and dropping the version check each fail tests.
