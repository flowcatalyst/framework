<!-- types: Table, Key, Interval, Retention, Partitions, Declared, Option, TableStatus, PartitionStatus, PassResult, EnsureResult, ArchiveRecord, Step, Admin -->
# Go: partitioned tables

Package `partitions` implements the [partitions contract](../../contracts/partitions.md): range-partitioned
tables whose partitions are created ahead of time and dropped whole (or archived to an object store first),
with a critical health check that goes down when the next partition is missing. Go only for now (the Java
framework follows). PostgreSQL only.

## When to partition, and when not

Partition a table that is **large, append-mostly and time-ordered**, and that is queried **by id or by a
recent time window**: readings, telemetry, logs, history, audit. The reason is retention: `DELETE` at volume
costs as much WAL and vacuum as the data deleted, and dropping a partition is O(1).

Do not partition:

- master data and small tables (the cost is complexity, the gain nothing);
- tables mostly queried **without** the key (every query then scans every partition);
- with an interval so small that there would be thousands of partitions (a table with daily partitions kept a
  year is 365; hourly is not supported for this reason).

## Choosing the key

- **`ByID("rdg")`** partitions on the table's id, `<prefix>_<TSID>`. The primary key stays `id`, the database
  still enforces its uniqueness, and `WHERE id = $1` prunes to one partition. Use it for tables whose rows
  are made with the framework's ids (`id.New`, `Ids.Prefixed`). Every id in the table must carry that prefix.
- **`ByColumn("recorded_at")`** partitions on a `timestamptz` column. PostgreSQL then requires every unique
  constraint, the primary key included, to contain that column: the primary key becomes `(id, recorded_at)`
  and `id` alone is no longer unique in the database.
- **`ByDateColumn("service_day")`** is the same on a `date` column: a service day, a business date. The bounds are
  date literals (every interval starts at UTC midnight, so they are exact), and you store the date itself rather
  than midnight UTC in a timestamp. A `date` column needs `ByDateColumn` and a `timestamptz` one `ByColumn`;
  start-up refuses the other pairing and a `timestamp` without time zone.

## Choosing the interval

`Day`, `Week` (ISO, Monday to Monday), `Month` and `Year` (the calendar year, all in UTC). Pick the interval so
that a table has tens, not thousands, of partitions: daily for a high-volume table kept weeks, monthly for most.
**`Year` is for long-retention, low-volume tables**, such as one row per vehicle trip kept for seven years:
`Retention: partitions.DropAfter(7 * 366 * 24 * time.Hour)` is then eight partitions. The retention is at least
one interval, so for `Year` at least 366 days; a partition goes only when its whole year is past the cutoff, so
the oldest data on disk can be nearly a year older than the retention.

## The migration

The framework never creates your table: the table is yours, in your migration, with the exact shape below.
Start-up checks it and fails naming the DDL it expected.

A `ByID` table (note `COLLATE "C"` **on the column**: the bounds compare as text, only byte order is TSID order,
and PostgreSQL prunes an id lookup only when the column itself is in the key's collation):

```sql
CREATE TABLE readings (
    id          text COLLATE "C" PRIMARY KEY,
    recorded_at timestamptz NOT NULL,
    device_id   text        NOT NULL,
    value       double precision NOT NULL
) PARTITION BY RANGE (id);

CREATE INDEX readings_device_idx ON readings (device_id);
```

A `ByColumn` table:

```sql
CREATE TABLE telemetry (
    id          text        NOT NULL,
    recorded_at timestamptz NOT NULL,
    payload     jsonb       NOT NULL,
    PRIMARY KEY (id, recorded_at)
) PARTITION BY RANGE (recorded_at);
```

Do not create a `DEFAULT` partition (start-up refuses a table that has one), and do not create partitions
in the migration: the module creates them, including the first, before the application serves.

## Declaring and building

```go
pd := partitions.Declare(app) // in main: contributes the migrations of fw_partition_archives and fw_partition_passes
```

```go
// in build, after database.Migrate
ps, err := partitions.Build(ctx, b, pd, dbs,
	partitions.WithTable(partitions.Table{
		Name:      "readings",
		By:        partitions.ByID("rdg"),
		Interval:  partitions.Month,
		Ahead:     3, // the current month and three more are always created
		Retention: partitions.DropAfter(90 * 24 * time.Hour),
	}),
	partitions.WithTable(partitions.Table{
		Name:      "telemetry",
		By:        partitions.ByColumn("recorded_at"),
		Interval:  partitions.Week,
		Ahead:     4,
		Retention: partitions.ArchiveThenDropAfter(180*24*time.Hour, "archive"),
	}),
	partitions.WithObjectStores(stores), // the stores that ArchiveThenDropAfter names
	partitions.WithLocks(lockInstance),  // one replica runs the loop
)
```

Tables are code, not configuration: there is no `partitions` section. A table in a database instance other
than the default one says so with `Database: "analytics"`; migrations run on the default instance only, so
for that instance create `fw_partition_archives` yourself if it archives, and `fw_partition_passes` (the last pass
of its tables is not recorded without it; start-up warns once).

`Build` checks the declarations and the tables, runs the first ensure pass and registers the loop
`partitions` (every hour by default, `partitions.Every(d)` changes it) and a critical health check
`partitions:<table>` per table.

## Choosing the retention

`Retention` is required: a table without one does not start.

| Retention | Does |
|---|---|
| `DropAfter(d)` | drops a partition when its whole range ended at least `d` ago |
| `ArchiveThenDropAfter(d, "store")` | writes the partition to the named object store as gzip'd NDJSON, verifies it, records it in `fw_partition_archives`, then drops it |
| `KeepForever()` | removes nothing: the decision is written down |

`d` is at least one interval. A partition is removed only when its **whole** range is past the cutoff, so
with monthly partitions and `DropAfter(90 days)` the oldest data on disk is between 90 days and 90 days plus a
month old. The current and future partitions are never removed whatever the clock says.

An archive is NDJSON (`row_to_json`, one object per line) in objects of about 16 MiB compressed (an object
store takes whole values; `partitions.ArchiveSegmentBytes(n)` changes it, keep it below the store's
`MaxBytes`), at `partitions/<schema>.<table>/<partition>.ndjson.gz` and `.part2.ndjson.gz`, and so on. A
The partition is **detached first** and archived from the detached table, so a write cannot slip in between the
archive and the drop (an insert into that range fails, an update through the parent finds nothing). A failure at
any later step (the put, the read-back check, the manifest) drops nothing, leaves the table detached (it is
not re-attached), is logged and counted (`fw_partition_failures_total{step="archive"}`), and is retried by the
next pass; a pass that died after the detach is finished the same way (recovery: detached tables with this
table's partition names that are due and carry the mark the detach sets as their table comment; a table of
such a name that the module did not detach is never touched). A partition whose archive keeps failing is never dropped: watch the
counter. A detached table of a partition's name in the current or future range makes the ensure pass fail
loudly instead of leaving inserts without a partition.

## Queries on a ByID table

An id lookup needs nothing. A **time window** is written with the bounds of the window as ids, so the planner
prunes to the overlapping partitions:

```go
lo, hi := partitions.IDRange("rdg", from, to)
rows, err := pool.Query(ctx, "SELECT id, value FROM readings WHERE id >= $1 AND id < $2 AND device_id = $3", lo, hi, device)
```

Do not filter with a function of the id (`substr(id, ...)`): it defeats pruning. The window selects rows whose
**id** carries a millisecond in `[from, to)`; if the row's `recorded_at` is not the moment the id was made,
filter on `recorded_at` as well.

## What the health check means

`partitions:<table>` is critical and is **down when the partition for the next interval does not exist**:
inserts will fail from the boundary. It reads the catalog on every check. The loop normally heals it; a down
check means the passes are failing (read `background:partitions`, the logs at ERROR and
`fw_partition_failures_total`), or the loop is gated by a lock nobody holds, or the database user cannot
create tables. `fw_partitions_ahead{table}` is how many partitions beyond the current one exist.

## Imports and backfills

Rows older than the window the loop keeps need their partitions first:

```go
res, err := ps.Ensure(ctx, "readings", from, to) // creates the partitions covering [from, to]; at most 400
// res.Created: how many it created; res.AlreadyDue: how many of the range retention removes at the next pass
```

Retention removes a backfilled partition at the next pass if it is older than the retention, so import within
it, or declare a longer one; `res.AlreadyDue` says how many partitions of the range are already past it, so warn
before relying on them. The refusals are typed (`partitions.ErrRangeInvalid`, `ErrRangeTooLarge`,
`ErrTableNotDeclared`, `ErrDetachedLeftover`; `errors.Is`).

## Status, and the operator actions

`ps.Status(ctx)` and `ps.TableStatus(ctx, "readings")` read the state of the tables from the catalog (a fixed
number of queries per table, never a row count): the declaration, `Healthy` (the health check's own decision), the
`LastPass` (recorded in the database, so any replica sees what the loop's replica did), every partition with its
`Position`, size, estimated rows, `RemovalDue` (when retention removes it) and `Archived`, the detached tables a
dead pass left and the strays. `ps.Archives(ctx, "telemetry", after, limit)` lists the archived partitions, newest
first, each with the key of its first object in the store.

Two actions are for an operator, with the acting principal's `fw.Context` for the record:
`ps.RunPass(ctx, by, "")` runs the loop's pass now (for one table, or every table with `""`) and returns a
`PassResult` per table with the steps that failed; `ps.EnsureAs(ctx, by, "readings", from, to)` is `Ensure` for an
operator. A manual pass never overlaps the loop's: while one runs, here or on another replica, it is refused with
`partitions.ErrPassRunning`, never queued. Each action logs `partitions: manual pass` or `partitions: manual
ensure` at INFO with the principal's id, the table, the range and the outcome; the framework has no audit store for
an operator's action, so that line is the record. Not provided, on purpose: dropping or detaching a partition by
hand, changing a retention (it is code), restoring an archive.

## The admin section (operators only)

Package `partitions/partitionsadmin` puts all of this behind the application admin, headless: five typed
operations (the tables, one table, its archives, run a pass, ensure a range), the permissions
`{app}:admin:partitions:view` and `:manage` (`partitionsadmin.View(app)`, `Manage(app)`, so a server-rendered
page gates on the same permission), and a navigation entry. A partition holds every client's rows, so the section is
**operators-only**: besides the permission the caller must be a platform operator (tier `ANCHOR`); a PARTNER, a
CLIENT and a caller with no tier get `403 FORBIDDEN` and the navigation does not show the section
([admin](admin.md), operators-only sections).

```go
pa := partitionsadmin.Declare(adm) // at declaration time, after admin.Declare
```

```go
// in build, after partitions.Build
routes, err := pa.Build(ps)
srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{skeleton, routes}, ...)
```

Declare the **operators' role**, which holds these permissions, as a role that is **not** client-managed (never
`WithClientManaged()`): client-managed roles are the ones a client's own administrators may assign. With
`fc.AdminDefinitions(adm)` and `flowcatalyst.WithPermissionMapping(mapper)` start-up refuses a client-managed role
that holds the permissions, directly or through `orders:admin:*:*` in the mapping, naming the role and the
permission. The suggested role `admin` that `AdminDefinitions` adds (not client-managed) holds them.

## Unique keys

A unique constraint on a partitioned table must contain the partition key. For a `ByColumn` table that rules
out a unique business key on its own (a device serial, an external reference). Either include the timestamp
in the key, or keep the business key in a **small side table** that is not partitioned and is written in the
same transaction:

```sql
CREATE TABLE telemetry_refs (
    external_ref text PRIMARY KEY,
    id           text NOT NULL
);
```

The use case inserts into `telemetry_refs` first; a duplicate fails there, in the database. The side table
holds one narrow row per business key, so it can stay (or be pruned by its own, cheap job).
`ByID` tables avoid the problem for `id` itself.

## Converting an existing table

Planned, not built. The approach: a new partitioned parent, the old table attached as one partition with a
pre-validated `CHECK` constraint (no rewrite), then the module creates the rest. See the large-table
approach in the platform repository's `docs/large-table-schema-changes.md`.

## Testing

`fwtest` gives a real PostgreSQL. The module reads the application's clock (`fw.WithClock`), so a test
advances a `fwtest.Clock` and calls `ps.Pass(ctx)` (one ensure-then-retention pass, the loop's work) to see
partitions created and dropped.
