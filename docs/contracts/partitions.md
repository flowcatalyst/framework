# Contract: partitioned tables

Status: Go first, 2026-11-14 (guide: `guide/go/partitions.md`); Java to follow. PostgreSQL only: MySQL is
not supported, because the framework's database layer is PostgreSQL only.

A large append-mostly table (readings, telemetry, logs, history, audit) cannot be cleaned with `DELETE` at
volume: the WAL and vacuum cost equal what is deleted. A range partition dropped whole is O(1). Without
help every application writes its own manager. The platform's experience with one: partitioning on a
timestamp forces the primary key to `(id, ts)`, loses database-enforced uniqueness on `id`, and makes a
lookup by id scan every partition. Framework ids are `<prefix>_<TSID>` with a fixed prefix per kind and a
fixed-width, time-ordered TSID (write-path contract section 8), so a table can be range-partitioned **on its
id**: the primary key stays `id` and an id lookup prunes to one partition. This contract specifies the module
that creates partitions ahead of time, removes old ones (optionally writing them to an object store first)
and fails loudly when it cannot.

## 1. Declaration (code, not configuration)

Which tables are partitioned and how long they are kept are facts of the application, the same in every
deployment, so they are code (config-and-code contract, section 1): there is no `partitions` configuration
section and no configuration key. Each table is one value:

| Field | Meaning |
|---|---|
| `Name` | the table, optionally schema-qualified (`readings`, `telemetry.readings`); each part `[a-z_][a-z0-9_]*` |
| `Database` | a database instance name; empty = the default instance |
| `By` | `ByID(prefix)` (key column `id`; `ByIDColumn(column, prefix)` for another column) or `ByColumn(column)` |
| `Interval` | `Day`, `Week` or `Month` |
| `Ahead` | future partitions kept beyond the current one: 1 to 24 |
| `Retention` | **required**: `DropAfter(d)`, `ArchiveThenDropAfter(d, storeName)` or `KeepForever()` |

Retention has no default and a missing one is a start-up error: nothing is deleted that nobody wrote down.
`d` must be at least one interval (the nominal widths: day 24 h, week 7 days, month 31 days). Start-up errors,
all reported together as the framework's `StartupError` naming `table <name>`: a table declared twice (also
under two spellings of one table, `readings` and `public.readings`), a name whose partition names would pass
63 bytes (`len(table) + 10 > 63`), an unknown object store (or none given while one is named), a prefix that
fails `ValidIDPrefix`, `Ahead` out of range, a missing key, interval or retention.

## 2. Intervals, names and bounds

All intervals are in **UTC**. `Day` is the calendar day; `Week` is the ISO week starting Monday 00:00 UTC;
`Month` is the calendar month. The start of the interval that contains an instant, and the start of the next
interval, are the only two operations (a month's next start is the first of the following month).

**Partition name:** `<table>_p<YYYYMMDD>` of the interval's start, the same shape for every interval so names
sort in time order. The partition is created in the parent's schema. `<table>` is the unqualified name.

**Bounds** are `FOR VALUES FROM (lower) TO (upper)`: lower inclusive, upper exclusive (PostgreSQL's RANGE),
where `upper` is the bound of the next interval's start.

- `ByColumn(col)`: the bound of an instant `t` is `t` in UTC as the `timestamptz` literal
  `YYYY-MM-DD HH:MM:SS+00` (for example `2026-11-01 00:00:00+00`).
- `ByID(prefix)`: the bound of an instant `t` is `prefix + "_" + EncodeTSID(uint64(t.UnixMilli()) << 22)`: the
  TSID layout is 42 bits of milliseconds, a 12-bit sequence and 10 random bits, so `ms << 22` is the smallest
  TSID that carries that millisecond. An instant before 1970 is bound as 1970-01-01 (milliseconds clamped to
  0). The encoded text is 13 characters of the Crockford alphabet, whose characters ascend in ASCII, so
  **byte order of the text is TSID order**.

An id belongs to the interval that its **own** millisecond decides. The generator may borrow the next
millisecond when more than 4096 ids are made in one (write-path section 8); such an id, made at the last
millisecond of an interval, carries the next interval's millisecond and sorts into the next partition. That
is correct, not a defect: the partition an id belongs to is a function of the id alone.

**Time-window queries** on a `ByID` table use `IDRange(prefix, from, to) (lo, hi)` (the two id bounds):
`WHERE id >= lo AND id < hi` selects the rows whose id carries a millisecond in `[from, to)` and prunes to the
overlapping partitions. A function of the id in the predicate defeats pruning. The rows' ids must carry the
declared prefix: a row with another prefix has no partition (and the insert fails).

**Advisory lock key** (section 4): the FNV-1a 64-bit hash (offset `14695981039346656037`, prime
`1099511628211`) of the UTF-8 bytes of `fw-partitions:<schema>.<table>` (the resolved schema and table
names), as a signed 64-bit integer. It is distinct from the migration runner's `AdvisoryLockKey`.

The shared vectors are `conformance/partitions/bounds.json` (58 cases: every interval, both keys, a month and
a year rollover, an ISO week that crosses the year (2026-12-31 and 2027-01-03 are both in the week starting
2026-12-28), a leap day, the millisecond before and at a bound, the epoch; id bounds of arbitrary instants;
three lock keys).

## 3. Collation and start-up validation

Text range bounds compare under the partition key's collation. The id column of a `ByID` table **must be
declared `COLLATE "C"`** (byte order). With a locale collation the bounds would not match TSID order.

After the application's migrations ran (the application calls `Migrate` first, then `Build`), for each table
on its database instance the module checks and, for any mismatch, fails start-up with a message that names
the table, says what is wrong and the DDL it expects:

1. the table exists;
2. it is `PARTITION BY RANGE` on exactly one plain column, the declared one (read from
   `pg_partitioned_table`; `LIST`, `HASH`, an expression or another column is a mismatch);
3. `ByID`: the key column is `text` or `varchar`, and both the partition key's collation and **the column's own
   collation** are `C` (or `POSIX`);
   `ByColumn`: the column is `timestamptz` (PostgreSQL itself already requires every unique constraint and
   the primary key to include the partition key);
4. the table has **no DEFAULT partition** (a default partition holding rows blocks creating the later
   partitions that would overlap them; the module fails loudly instead of collecting strays);
5. when the retention archives: `fw_partition_archives` exists on that database instance (the framework's own
   migration, section 7, runs on the default instance only).

Then one **ensure pass** runs synchronously inside `Build`, before the application's started actions, so the
first insert never finds no partition.

The column's own collation matters, not only the key's: with `id text` in the default collation and
`PARTITION BY RANGE (id COLLATE "C")`, PostgreSQL routes rows by the key collation but does **not** prune
`WHERE id = $1` (the comparison runs in the column's collation; the plan scans every partition; tested with
`EXPLAIN`), and it refuses a primary key on such a table. So the column itself must be declared
`id text COLLATE "C"`, and start-up says so with the column DDL.

## 4. Ensure pass (create ahead)

For the interval containing `now` and the next `Ahead` intervals, create the missing partitions:

```sql
CREATE TABLE IF NOT EXISTS <schema>.<name> PARTITION OF <parent> FOR VALUES FROM ('<lower>') TO ('<upper>')
```

in one transaction that first runs `SET LOCAL lock_timeout = '10s'` and takes
`pg_advisory_xact_lock(<LockKey>)` (section 2), then lists the attached partitions and creates only the
missing ones. Replicas start together, and concurrent `CREATE ... IF NOT EXISTS` can still fail on the
catalog; the advisory lock serialises them per table. `CREATE TABLE ... PARTITION OF` takes a strong lock on
the parent; creating ahead keeps it off the insert path, and `lock_timeout` makes a pass fail (and retry)
rather than queue every insert behind a long query. A **DEFAULT partition is never created.** `CREATE TABLE IF NOT EXISTS ... PARTITION OF` silently does nothing
when a table of that name exists, attached or not; so the ensure checks first, and a table of a partition's
name that exists but is **not attached** (a detached leftover in a current or future range) fails the pass
loudly, naming the table, counted as `ensure` failure; at start-up it is a start-up error.

`Ensure(table, from, to)` creates the partitions that cover an arbitrary window (imports, backfills). It is
bounded: more than 400 partitions in one call, or `from` before 1970, or `to` before `from`, is refused.
Retention removes a backfilled partition at the next pass if it is older than the retention.

## 5. Retention pass

A partition is **due** when its upper bound is not after `now - retention` (its whole range lies past the
cutoff). `KeepForever` has no retention pass. Only partitions whose names parse as this framework's names for
the table (section 2: the right table, `_p`, eight digits that are a real start of the declared interval, and
the parent's schema) are considered; anything else attached to the parent is left alone and logged once at
WARN per process.

**Skew guard.** Whatever the retention says, the pass refuses a partition whose upper bound is after
`now - one interval` (the nominal width of section 1). The current and future partitions are therefore never
removed, whatever the clock says or a mis-set retention would. The guard is defence in depth: a declared
retention is at least one interval, so it is not reached by a valid declaration unless the clock moves.

- **`DropAfter`:** in one transaction, `SET LOCAL lock_timeout`, `ALTER TABLE parent DETACH PARTITION p`, then
  `DROP TABLE p`. A failure rolls the detach back; the partition stays attached and the next pass retries.
  **`DETACH ... CONCURRENTLY` is not used:** it cannot run in a transaction and leaves a detached-pending
  state that an interrupted run must finish, which is state to recover across passes and replicas. The plain
  form takes a stronger lock on the parent for an instant; `lock_timeout` bounds the wait.
- **`ArchiveThenDropAfter(d, store)`: detach first, so the archive is exactly what is dropped.** A row count
  after the archive cannot see an UPDATE, or a delete balanced by an insert, made between the snapshot and the
  drop; detaching first removes the window instead of checking for it.
  1. **Detach** the due partition in a short transaction (`lock_timeout` as above). From then on no write
     reaches it through the parent: an insert into its range fails (no partition), an update or delete through
     the parent finds no row. Past retention that is correct, and loud.
  2. **Archive from the detached table:** stream the rows in a read-only transaction, `SELECT
     row_to_json(t)::text FROM <partition> t`, one JSON object per line (NDJSON), gzip-compressed, never the
     partition in memory.
  3. **An object store takes whole values only** (object-stores contract section 1: put and get of byte
     arrays, at most `maxBytes`), so the archive is cut into objects of about 16 MiB compressed (settable,
     `ArchiveSegmentBytes`): each is a complete gzip stream. Object 1 has the key
     `partitions/<schema>.<table>/<partition name>.ndjson.gz`; object `k > 1`
     `partitions/<schema>.<table>/<partition name>.part<k>.ndjson.gz`. A partition without rows is one empty
     object. Content type `application/gzip`.
  4. Every object is **verified** right after its put: read back, same size, same SHA-256.
  5. A **manifest** row per object is recorded in `fw_partition_archives` (section 7), replacing the rows of
     an earlier archive of the same partition (keys of an earlier, longer archive are then deleted).
  6. **`DROP TABLE`** the detached table.

  Any failure after the detach **leaves the table detached** (it is never re-attached: the data is intact and
  the state is visible), is logged at ERROR, counted (section 6), and retried by the next pass. The retry is
  idempotent: re-archiving overwrites the same keys and replaces the manifest rows. A partition whose archive
  keeps failing is never dropped.
  **Recovery:** every retention pass of an archiving table also looks, in the parent's schema, for plain
  tables (not partitions, not partitioned) whose names parse as this table's partition names and whose range is
  due, **and that carry the detach mark**, and archives and drops them as above. That finishes a pass that died
  between the detach and the drop. The mark is the table comment `fw-partitions: detached from <schema>.<table>
  for archiving`, set in the detach's own transaction, so a table of a partition's name that this module did not
  detach (a manual copy, a restore) is never archived or dropped. A detached table that is not due, whose name is
  not ours, or that has no mark, is left alone.

## 6. The loop, health and metrics

**One background loop** named `partitions` (background-and-metrics contract section 1) serves all tables on
all database instances: one lock, one health check, tables run one after another and a table's failure does
not stop the others (the pass returns the joined errors, so the loop counts a failed run and its own health
check follows). The default wait is **1 hour**; it is a code default changed with the `Every` option (not
configuration, config-and-code contract). The first run is one minute after start (`Build` already ran the
ensure pass; the first loop pass is for retention). With `WithLocks`, the loop is gated by the service lock
`fw-partitions` (lease 5 minutes, refreshed while a pass runs), so one replica runs each pass; without it every
replica runs, which is safe (creation is serialised by the advisory lock, a drop is one transaction) but
wasteful. Each pass runs, per table, the ensure pass then the retention pass.

**Health:** the check `partitions:<table>` is **critical** and is down when the partition for the **next**
interval does not exist (inserts start failing at the boundary), read from the catalog on every check, not
from memory. Its reason names no more than the interval's date.

**Metrics** (names follow the background-and-metrics rules; `table` is the schema-qualified name):

| Name | Type | Labels | |
|---|---|---|---|
| `fw_partitions_ahead` | gauge | `table` | partitions that exist after the current interval, set at each pass |
| `fw_partition_failures_total` | counter | `table`, `step` | `step` is `ensure`, `retention` (a failed drop or listing) or `archive` |
| `fw_partitions_dropped_total` | counter | `table` | |
| `fw_partitions_archived_total` | counter | `table` | partitions archived and verified (a retried archive counts again) |
| `fw_partition_rows_archived_total` | counter | `table` | |

Each create, drop and archive is logged at INFO with `table` and `partition`.

## 7. The manifest table

The framework's own migration set (module id `partitions`, `Declare` contributes it like the locks database
driver contributes `fw_lock`) creates:

```sql
CREATE TABLE fw_partition_archives (
    table_name     text        NOT NULL,   -- schema.table
    partition_name text        NOT NULL,
    part           int         NOT NULL,   -- 1-based object number
    row_count      bigint      NOT NULL,   -- rows in this object
    byte_count     bigint      NOT NULL,   -- size of this object
    sha256         text        NOT NULL,   -- hex digest of this object
    object_key     text        NOT NULL,
    archived_at    timestamptz NOT NULL,
    PRIMARY KEY (table_name, partition_name, part)
);
```

A plain small table, no foreign key. It is created for every application that declares the module, whether
or not any table archives (the cost is one empty table; declaring the module is the application's decision to
use partitions). It lives on the default instance (where migrations run); a table on another instance that
archives needs the table created there by hand, and start-up says so.

## 8. Not in this contract

Converting an existing large table to a partitioned one is **planned, not built**: a new partitioned parent,
the old table attached as one partition with a pre-validated `CHECK` constraint (the approach in
`docs/large-table-schema-changes.md` of the platform repository), so no rows are rewritten. Also not here:
`HASH` and `LIST` partitioning, sub-partitioning, a different retention per partition, reading an archive back
into a table, and MySQL.

## 9. Tests

Unit: the conformance vectors; every generated TSID sorts into `[lower, upper)` of the interval its own
millisecond belongs to, and into no other, including the borrowed-millisecond edge; `IDRange`; name parsing;
declaration errors; due-ness including the skew guard. Integration on PostgreSQL (`fwtest`): both key types;
the start-up ensure; `EXPLAIN` of an id lookup and of an `IDRange` window touches only the matching partitions;
the loop with a fake clock creating and dropping as it advances; the archive (NDJSON compared row for row, the
manifest, several objects); an archive failure and a corrupt put leave the table detached, drop nothing and are
retried; a write between archive and drop cannot reach the detached table, so the archive holds the final data;
recovery of a detached table; ensure refusing a detached table of a partition's name; key versus column
collation (no pruning, start-up refusal); two replicas sharing a lock store, the one that does not hold the
lock skipping and doing no DDL; concurrent ensure on one table from two replicas; every start-up validation
failure; the health check going down when the next partition is dropped by hand.
