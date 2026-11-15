<!-- types: PartitionsAdminModule, PartitionsAdminApi, PartitionsModule, Partitions, Table, Key, Interval, Retention, KeyKind, KeyColumnType, RetentionKind, TableStatus, PartitionStatus, RetentionStatus, LastPass, ArchivedPartition, ArchiveRecord, PassResult, EnsureResult, Step, StepStatus, PassTrigger, Position, PartitionRefusedException, PartitionsException, PartitionNames -->
# Partitioned tables

Module `framework-partitions` (package `io.flowcatalyst.framework.partitions`) implements the
[partitions contract](../contracts/partitions.md): range-partitioned tables whose partitions are created ahead of
time and dropped whole (or archived to an object store first), with a critical health check that goes down when
the next partition is missing. PostgreSQL only. The [Go build](go/partitions.md) behaves the same: partition names,
bounds, DDL, tables, health check and metric names are identical. The operators-only admin section (contract
section 13) is the module `framework-partitions-admin`, described under [The admin section](#the-admin-section-operators-only)
below.

## When to partition, and when not

Partition a table that is **large, append-mostly and time-ordered**, and that is queried **by id or by a recent
time window**: readings, telemetry, logs, history, audit. The reason is retention: `DELETE` at volume costs as much
WAL and vacuum as the data deleted, and dropping a partition is O(1).

Do not partition master data and small tables, tables mostly queried **without** the key (every query then scans
every partition), or with an interval so small that there would be thousands of partitions (hourly is not supported
for this reason).

## Choosing the key

- **`Key.byId("rdg")`** partitions on the table's id, `<prefix>_<TSID>`. The primary key stays `id`, the database
  still enforces its uniqueness, and `WHERE id = ?` prunes to one partition. Use it for tables whose rows are made
  with the framework's ids (`Ids.generate("rdg")`). Every id in the table must carry that prefix.
  `Key.byIdColumn("event_id", "evt")` is the same on another column.
- **`Key.byColumn("recorded_at")`** partitions on a `timestamptz` column. PostgreSQL then requires every unique
  constraint, the primary key included, to contain that column: the primary key becomes `(id, recorded_at)` and `id`
  alone is no longer unique in the database.
- **`Key.byDateColumn("service_day")`** is the same on a `date` column: a service day, a business date. The bounds
  are date literals. A `date` column needs `byDateColumn` and a `timestamptz` one `byColumn`; start-up refuses the
  other pairing and a `timestamp` without time zone.

## Choosing the interval

`Interval.DAY`, `WEEK` (ISO, Monday to Monday), `MONTH` and `YEAR` (the calendar year), all in UTC. Pick the
interval so that a table has tens, not thousands, of partitions: daily for a high-volume table kept weeks, monthly
for most. **`YEAR` is for long-retention, low-volume tables**: a retention of seven years is then eight partitions.
A retention is at least one interval, so for `YEAR` at least 366 days, and a partition goes only when its whole year
is past the cutoff.

A partition is named `<table>_p<YYYYMMDD>` of its interval's start (`readings_p20261101`), in the parent's schema,
for every interval.

## The migration

The framework never creates your table: the table is yours, in your migration, with the exact shape below. Start-up
checks it and fails naming the DDL it expected.

A `byId` table (note `COLLATE "C"` **on the column**: the bounds compare as text, only byte order is TSID order, and
PostgreSQL prunes an id lookup only when the column itself is in the key's collation):

```sql
CREATE TABLE readings (
    id          text COLLATE "C" PRIMARY KEY,
    recorded_at timestamptz NOT NULL,
    device_id   text        NOT NULL,
    value       double precision NOT NULL
) PARTITION BY RANGE (id);
```

A `byColumn` table:

```sql
CREATE TABLE telemetry (
    id          text        NOT NULL,
    recorded_at timestamptz NOT NULL,
    payload     jsonb       NOT NULL,
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);
```

Do not create a `DEFAULT` partition (start-up refuses a table that has one), and do not create partitions in the
migration: the module creates them, including the first, before the application serves. Your table's migration is
part of your module's migration set: `DatabaseModule` applies every module's set when the database is built, and
`PartitionsModule` validates the tables after that, so the order of the module list does not matter.

## Declaring the module

Tables are code, not configuration: there is no `partitions` section. A table is a `Table` value; the module takes
them in its builder, as `LocksModule` takes its stores:

```java
App.builder()
    .module(new DatabaseModule())
    .module(
        ObjectStoresModule.builder()
            .store("archive", ObjectStoreFactories.memory()) // S3ObjectStores.s3(...) in production
            .build())
    .module(
        LocksModule.builder()
            .store("main", DatabaseLockStores.database()) // with LocksDatabaseModule
            .build())
    .module(
        PartitionsModule.builder()
            .table(
                Table.named("readings")
                    .by(Key.byId("rdg"))
                    .interval(Interval.MONTH)
                    .ahead(3) // the current month and three more are always created
                    .retention(Retention.dropAfter(Duration.ofDays(90))))
            .table(
                Table.named("telemetry")
                    .by(Key.byColumn("recorded_at"))
                    .interval(Interval.WEEK)
                    .ahead(4)
                    .retention(Retention.archiveThenDropAfter(Duration.ofDays(180), "archive")))
            .locks() // one replica runs the loop
            .build())
    .start();
```

The module contributes the migration set (module id `partitions`) that creates `fw_partition_archives` and
`fw_partition_passes` on the **default** database instance. A table in another instance says so with
`.database("analytics")`; migrations run on the default instance only, so for that instance create
`fw_partition_archives` yourself if it archives, and `fw_partition_passes` (the last pass of its tables is not
recorded without it; start-up warns once per instance). Make `fw_partition_passes` as in the module's
`V0002__create_partition_passes_table.sql` and `V0003__add_partition_pass_trigger.sql`: a table made in the shape of
V0002 alone still records and reads, but without what ran the last pass and who, and start-up warns once that the
columns `pass_trigger` and `actor` are missing.

At start-up the module checks every declaration (all problems at once, each naming `table <name>`), validates every
table against its database, runs one ensure pass before the application's `started` hooks, adds a critical health
check `partitions:<table>` per table (named as declared), and registers the background loop `partitions` (every hour
by default; `.every(Duration)` changes it; the first pass is one minute after start). `.locks()` gates the loop with
the service lock `fw-partitions` on the default lock store, so one replica runs each pass; `.archiveSegmentBytes(n)`
sets the size an archive object is cut at. Metrics: `fw_partitions_ahead{table}`,
`fw_partition_failures_total{table,step}` (`ensure`, `retention`, `archive`, `record`),
`fw_partitions_dropped_total`, `fw_partitions_archived_total` and `fw_partition_rows_archived_total`.

## Choosing the retention

`Retention` is required: a table without one does not start.

| Retention | Does |
|---|---|
| `Retention.dropAfter(d)` | drops a partition when its whole range ended at least `d` ago |
| `Retention.archiveThenDropAfter(d, "store")` | writes the partition to the named object store as gzip'd NDJSON, verifies it, records it in `fw_partition_archives`, then drops it |
| `Retention.keepForever()` | removes nothing: the decision is written down |

`d` is at least one interval. A partition is removed only when its **whole** range is past the cutoff, so with monthly
partitions and `dropAfter(90 days)` the oldest data on disk is between 90 days and 90 days plus a month old. The
current and future partitions are never removed whatever the clock says (the skew guard).

An archive is NDJSON (`row_to_json`, one object per line) in objects of about 16 MiB compressed, at
`partitions/<schema>.<table>/<partition>.ndjson.gz` and `.part2.ndjson.gz` and so on (`Partitions.archiveKey` is the
first). The partition is **detached first** and archived from the detached table, so a write cannot slip in between
the archive and the drop (an insert into that range fails, an update through the parent finds nothing). A failure at
any later step (the put, the read-back check, the manifest) drops nothing, leaves the table detached (it is not
re-attached), is logged and counted (`fw_partition_failures_total{step="archive"}`), and is retried by the next pass.
A pass that died after the detach is finished the same way: recovery archives and drops detached tables with this
table's partition names that are due and carry the mark the detach sets as their table comment; a table of such a
name that the module did not detach is never touched. A partition whose archive keeps failing is never dropped: watch
the counter. A detached table of a partition's name in the current or future range makes the ensure pass fail loudly.

## Queries on a byId table

An id lookup needs nothing. A **time window** is written with the bounds of the window as ids, so the planner prunes
to the overlapping partitions:

```java
Key.IdRange window = Key.idRange("rdg", from, to);
// SELECT id, value FROM readings WHERE id >= ? AND id < ? AND device_id = ?   with window.lo(), window.hi()
```

Do not filter with a function of the id (`substr(id, ...)`): it defeats pruning. The window selects rows whose **id**
carries a millisecond in `[from, to)`; if the row's `recorded_at` is not the moment the id was made, filter on
`recorded_at` as well.

## What the health check means

`partitions:<table>` is critical and is **down when the partition for the next interval does not exist**: inserts will
fail from the boundary. It reads the catalog on every check. The loop normally heals it; a down check means the passes
are failing (read `background:partitions`, the logs at ERROR and `fw_partition_failures_total`), or the loop is gated by
a lock nobody holds, or the database user cannot create tables.

## Imports and backfills

Rows older than the window the loop keeps need their partitions first:

```java
EnsureResult res = partitions.ensure("readings", from, to); // the partitions covering [from, to]; at most 400
// res.created(): how many it created; res.alreadyDue(): how many of the range retention removes at the next pass
```

Retention removes a backfilled partition at the next pass if it is older than the retention, so import within it, or
declare a longer one; `alreadyDue()` says how many partitions of the range are already past it. The refusals are a
`PartitionRefusedException` whose `reason()` is `RANGE_INVALID`, `RANGE_TOO_LARGE`, `TABLE_NOT_DECLARED` or
`DETACHED_LEFTOVER`.

## Status, and the operator actions

`partitions.status()` and `partitions.tableStatus("readings")` read the state of the tables from the catalog (a fixed
number of queries per table, never a row count): the declaration, `healthy()` (the health check's own decision), the
`lastPass()` (recorded in the database, so any replica sees what the loop's replica did; its `trigger()` is `LOOP` or
`MANUAL` and its `actor()` the principal id of a manual pass, both empty when not recorded; the row is overwritten by
the next pass, so it is not a history or an audit trail), every partition with its `position()`, size, estimated rows,
`removalDue()` and `archived()`, the detached tables a dead pass left and the strays. `key()` is the declared `Key`:
read its `kind()` (`ID` or `COLUMN`), `column()`, `prefix()` and `columnType()` instead of parsing text. `rows()` is the
sum of the partitions' estimates and is empty when the table has partitions and none was analysed yet, which is not the
same as an empty table; `partitionsWithoutEstimate()` says how many partitions have no estimate. A partition dropped
while the status is read is left out. `partitions.archives("telemetry", after, limit)` lists the archived partitions,
newest first, each with the key of its first object in the store.

Two actions are for an operator, with the acting principal's `Context` for the record:
`partitions.runPass(by, "")` runs the loop's pass now (for one table, or every table with `""`) and returns a
`PassResult` per table with the steps that failed; `partitions.ensureAs(by, "readings", from, to)` is `ensure` for an
operator. A manual pass never overlaps the loop's: while one runs, here or on another replica, it is refused with a
`PartitionRefusedException` whose reason is `PASS_RUNNING`, never queued. Each action logs `partitions: manual pass`
or `partitions: manual ensure` at INFO with the principal's id and kind, the tier (when the context has one), the
correlation id, the table, the range and the outcome (`table=... outcome=ok principal=... tier=ANCHOR`); the framework has no audit store for an operator's action, so that
line is the record. Not provided, on purpose: dropping or detaching a partition by hand, changing a retention (it is
code), restoring an archive.

## The admin section (operators only)

`PartitionsAdminModule` (module `framework-partitions-admin`, package `io.flowcatalyst.framework.partitions.admin`)
puts all of this behind the application admin, headless: five typed operations (the tables, one table, its archives,
run a pass, ensure a range), the permissions `{app}:admin:partitions:view` and `:manage`
(`PartitionsAdminModule.view(app)` and `manage(app)`, so a server-rendered page gates on the same permission), and a
navigation entry (id `partitions`, path `/admin/partitions`). It is a module of its own because `framework-admin`
must not depend on `framework-partitions` (the admin skeleton is HTTP-only) and `framework-partitions` needs neither
HTTP nor the admin registry: the same split as `framework-locks-database` and `framework-auth-database`. List it after
`AdminModule` and `PartitionsModule`:

```java
App.builder()
    .module(AdminModule.of(APPLICATION))
    .module(new DatabaseModule())
    .module(PartitionsModule.builder().table(readings).locks().build())
    .module(PartitionsAdminModule.of(APPLICATION))
    .start();
```

A partition holds every client's rows, so the section is **operators-only**: besides the permission the caller must be
a platform operator (tier exactly `ANCHOR`); a `PARTNER`, a `CLIENT`, a caller with no tier and a lower-case `anchor`
get `403 FORBIDDEN` "this is for platform operators only" whatever permissions they hold (the wildcard included), and
the navigation does not show the section ([admin](admin.md), operators-only sections). It works in an ordinary admin
area and in one declared `AdminModule.Option.OPERATORS_ONLY`.

| Operation | Id | Permission | Answers |
|---|---|---|---|
| `GET /api/admin/partitions` | `listAdminPartitions` | view | `tables`, each without its partitions: `partitionCount`, `bytes`, `rows`, `partitionsWithoutEstimate`, `oldestFrom`, `nextRemovalDue` |
| `GET /api/admin/partitions/{table}` | `getAdminPartitionTable` | view | the table with every partition, `detached` and `strays`; `404 PARTITION_TABLE_NOT_FOUND` |
| `GET /api/admin/partitions/{table}/archives` | `listAdminPartitionArchives` | view | a page (`items`, `nextCursor`; `limit` 50, 1 to 200; `after`), newest first |
| `POST /api/admin/partitions/passes` | `runAdminPartitionPass` | manage | body `{"table": "..."}` or `{}`; `{"results": [...]}`; `404 PARTITION_TABLE_NOT_FOUND`, `409 PARTITION_PASS_RUNNING` |
| `POST /api/admin/partitions/{table}/ensure` | `ensureAdminPartitions` | manage | body `{"from", "to"}`; `{"created", "alreadyDue"}`; `404`, `400 PARTITION_RANGE_INVALID`, `400 PARTITION_RANGE_TOO_LARGE`, `409 PARTITION_DETACHED_LEFTOVER` |

A missing or malformed body field is `400 VALIDATION`. On the wire the table's `key` is an object (`kind` `ID` or
`COLUMN`, `column`, `prefix` for an ID key, `type` `TIMESTAMPTZ` or `DATE` for a COLUMN key), `lastPass` has `trigger`
and `actor` (omitted when not recorded), `retention.after` is an ISO-8601 duration (`PT2160H`), and `rows` is omitted
when no partition has an estimate yet; `partitionsWithoutEstimate` is always there. The types are
`PartitionsAdminApi.AdminPartition...`, named as in Go's OpenAPI document. A manual pass is recorded against the
caller's context (trigger `MANUAL`, the caller's id as `actor`, and the log line with the tier).

Give the permissions to a **role that is not client-managed** (never `withClientManaged()`): client-managed roles are
the ones a client's own administrators may assign. With the FlowCatalyst module, start-up refuses a client-managed role
that holds `{app}:admin:partitions:view` or `:manage`, directly or through `orders:admin:*:*` or
`orders:admin:partitions:*` in `withPermissionMapping`, naming the role and the permission (proved against this
section by `OperatorsOnlyGuardTest` in `framework-flowcatalyst`). The suggested `admin` role holds them.
`PartitionsAdminModule.section(app)` returns the declared section without handlers, for a test of those rules.

Tests (module `framework-partitions-admin`, a real PostgreSQL): `PartitionsAdminOperationsTest` (the five operations,
paging, a pass and its 409, ensure and each refusal), `PartitionsAdminOperatorsTest` (every tier, the navigation, the
OpenAPI marker, an operators-only area, and the gate and refusals of `conformance/admin/partitions.json`) and
`PartitionsAdminProjectionTest` (the vector's projection and durations, without a database). The partitions module
serves at most 200 archives a call, so at `limit=200` the section asks once more to say whether another page follows
(`nextCursor`).

## Unique keys

A unique constraint on a partitioned table must contain the partition key. For a `byColumn` table that rules out a
unique business key on its own (a device serial, an external reference). Either include the timestamp in the key, or
keep the business key in a **small side table** that is not partitioned and is written in the same transaction. `byId`
tables avoid the problem for `id` itself.

## Converting an existing table

Planned, not built (as in Go).

## Testing

`PostgresFixture` (the `framework-database` test-jar) gives a real PostgreSQL. The module reads the application's
`InstantSource` (`AppBuilder.instantSource`), so a test moves a clock and calls `partitions.pass()` (one
ensure-then-retention pass, the loop's work) to see partitions created and dropped. The module's own tests are
`PartitionsLifecycleTest`, `PartitionsStatusTest` and `PassRecordTest`; `BoundsConformanceTest` runs the shared vector
`conformance/partitions/bounds.json`, and `DdlPinTest` and `DdlGoParityTest` pin every statement against Go's.
