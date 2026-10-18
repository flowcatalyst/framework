<!-- types: Setting, Settings, Repository, Store, Current -->
# Go: per-client settings

Package `clientsettings` implements the [client-settings contract](../../contracts/client-settings.md)
(the Java page is [Per-client settings](../client-settings.md)). The table, the migration and the
JSON are the Java framework's: module id `client-settings`, table `fw_client_settings`
(`client_id`, `version`, `settings` JSONB), one row per client, one aggregate whose version covers
all of the client's settings, so a Go service can take over a Java database. The migration file is a
shared file (`go-sync-shared`), checked byte for byte, and the history row equals the Java vector.

## Definitions are code

```go
var SLA = clientsettings.MustSetting("tickets.sla", Sla{2880, 1440, 480, 60})
```

`NewSetting(key, default)` (or `MustSetting` for a package-level value) takes a key
`[a-z][a-z0-9._-]{0,99}` and a default; `T` is anything `encoding/json/v2` reads and writes (a
struct, an enum type with its own `UnmarshalJSON`, a string, a number, a slice). `WithRule(func(T)
string)` adds a rule: `""` is acceptable, anything else is a message safe to show a person; it runs
on every `With`, can be called alone (`Setting.Check`, in a use case's `Validate`), and a default
that breaks its own rule is refused. Keys are the application's namespace; two definitions with one
key share a value.

## Reading and writing

```go
db, _ := ...                       // the default database instance
store := clientsettings.NewStore(db)
sla, err := clientsettings.Read(ctx, store, client, SLA)    // the default when there is no row or no value
```

`Read` goes straight to the database, with no cache. A stored value that does not decode as `T` is
an error wrapping `clientsettings.ErrDecode` that names the key and the client; it is never replaced
by the default. A stored JSON `null` reads as the default.

There is no framework use case, endpoint or page for writing. A write is an ordinary use case whose
plan holds `Repository.Step`:

```go
cur, err := repo.ForClient(ctx, db, cx.Client())        // the stored row, or an empty version-0 aggregate
changed, err := clientsettings.With(cur.Settings(), SLA, newSLA)   // runs the rule; or Without(...)
plan := usecase.NewPlan(event, repo.Step(cx, cur, changed))        // insert at version 1, else version-checked update
```

`With` returns an error `client setting <key>: <message>` when the rule refuses (Java's
`IllegalArgumentException`). Two writers on one row: the loser is `STALE_AGGREGATE`; two first writes
at once: `UNIQUE_VIOLATION` (not retried). A `Settings` of another client than the Context's is
refused by the runner as `CROSS_CLIENT_WRITE`. The stored JSON is `{"key": value}`; `Repository`
is a `usecase.Repository[Settings, string]` (id = the client's code; a find of another client's id is
a miss).

## Differences from Java

- Generic functions instead of methods (`Read`, `Get`, `With`, `Without`, `IsSet`), because Go
  methods cannot have type parameters; `Current` carries the version that Java keeps on the aggregate.
- Integers read a JSON number with a zero fraction (`12.0`) as an integer, as Jackson does, for the
  exact types `int`, `int32` and `int64`; a real fraction is an error (Jackson truncates it), and a
  JSON string `"12"` for a number is an error (Jackson coerces it). Unknown members of a stored
  record are ignored and missing ones are zero, as in Java.
- A stored JSON `null` is the default in both `Read` and `Get`; Java's `ClientSettings.get` returns
  `null` for it.
- The text sent to PostgreSQL has sorted keys; the vector compares `jsonb`, which does not keep key
  order or number spelling.
- The contract's section 7 rulings (one row per client, no cache, an undecodable value is an error)
  are provisional, as in Java; they are the owner's to confirm.
