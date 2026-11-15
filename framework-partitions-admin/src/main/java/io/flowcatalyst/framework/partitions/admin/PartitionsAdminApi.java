package io.flowcatalyst.framework.partitions.admin;

import static io.flowcatalyst.framework.http.Rules.maxLength;

import io.flowcatalyst.framework.admin.AdminApi.Page;
import io.flowcatalyst.framework.http.NoBody;
import io.flowcatalyst.framework.http.Operation;
import io.flowcatalyst.framework.http.PathParam;
import io.flowcatalyst.framework.http.QueryParam;
import io.flowcatalyst.framework.http.Rule;
import io.flowcatalyst.framework.http.TypeRef;
import io.flowcatalyst.framework.http.Validator;
import io.flowcatalyst.framework.partitions.ArchiveRecord;
import io.flowcatalyst.framework.partitions.ArchivedPartition;
import io.flowcatalyst.framework.partitions.Interval;
import io.flowcatalyst.framework.partitions.Key;
import io.flowcatalyst.framework.partitions.KeyColumnType;
import io.flowcatalyst.framework.partitions.KeyKind;
import io.flowcatalyst.framework.partitions.LastPass;
import io.flowcatalyst.framework.partitions.PartitionStatus;
import io.flowcatalyst.framework.partitions.PassTrigger;
import io.flowcatalyst.framework.partitions.Position;
import io.flowcatalyst.framework.partitions.RetentionKind;
import io.flowcatalyst.framework.partitions.RetentionStatus;
import io.flowcatalyst.framework.partitions.StepStatus;
import io.flowcatalyst.framework.partitions.TableStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The wire types and operations of the partitions section (partitions contract section 13): the
 * state of every declared partitioned table, its archives, and two operator actions. The type names
 * are Go's OpenAPI names. Instants are RFC 3339 UTC, durations ISO-8601, an absent optional field is
 * omitted.
 */
public final class PartitionsAdminApi {
  private PartitionsAdminApi() {}

  /**
   * A step of a pass, as it is written on the wire: lower case, which is why the constants are.
   * {@code io.flowcatalyst.framework.partitions.Step} names them in upper case.
   */
  @SuppressWarnings("checkstyle:TypeName")
  public enum Step {
    /** Creating the partitions of the next intervals. */
    ensure,
    /** Dropping (or listing for archive) the partitions past retention. */
    retention,
    /** Writing the partitions to the object store before the drop. */
    archive
  }

  /**
   * What a table is partitioned on.
   *
   * @param kind {@code ID} (an id column whose values carry a prefix) or {@code COLUMN}
   * @param column the column
   * @param prefix the id prefix; only for an {@code ID} key
   * @param type {@code TIMESTAMPTZ} or {@code DATE}; only for a {@code COLUMN} key
   */
  public record AdminPartitionKey(KeyKind kind, String column, @Nullable String prefix, @Nullable KeyColumnType type) {}

  /**
   * A table's declared retention.
   *
   * @param kind what retention does to a partition past its time
   * @param after how long a partition is kept after its end; absent for {@code KEEP}
   * @param store the object store of an archive; absent unless the retention archives
   */
  public record AdminPartitionRetention(RetentionKind kind, @Nullable Duration after, @Nullable String store) {}

  /**
   * The last pass of a table, by whichever replica ran it. Not a history: the next pass overwrites it.
   *
   * @param endedAt when it ended
   * @param ensure the outcome of the ensure step
   * @param retention the outcome of the retention step
   * @param archive the outcome of the archive step
   * @param created partitions created
   * @param dropped partitions dropped
   * @param archived partitions archived
   * @param trigger {@code LOOP} or {@code MANUAL}; absent when it was not recorded
   * @param actor the principal id of a manual pass; absent when it was not recorded
   */
  public record AdminPartitionLastPass(
      Instant endedAt,
      StepStatus ensure,
      StepStatus retention,
      StepStatus archive,
      int created,
      int dropped,
      int archived,
      @Nullable PassTrigger trigger,
      @Nullable String actor) {}

  /**
   * What the manifest holds for an attached partition that was archived.
   *
   * @param objects objects written
   * @param rows rows archived
   * @param bytes bytes archived
   * @param archivedAt when the latest archive was made
   */
  public record AdminPartitionArchived(int objects, long rows, long bytes, Instant archivedAt) {}

  /**
   * One attached partition.
   *
   * @param name the partition's table name
   * @param from the start, inclusive
   * @param to the end, exclusive
   * @param position where it is against the module's clock
   * @param bytes its size with indexes and TOAST
   * @param rows the planner's estimate; absent until the partition was analysed
   * @param removalDue when retention removes it; absent for a table that keeps everything
   * @param archived the archive record, absent when none
   */
  public record AdminPartition(
      String name,
      Instant from,
      Instant to,
      Position position,
      long bytes,
      @Nullable Long rows,
      @Nullable Instant removalDue,
      @Nullable AdminPartitionArchived archived) {}

  /**
   * A table in the list: its declaration and totals, without its partitions.
   *
   * @param name the table, schema-qualified
   * @param database the database instance; absent for the default
   * @param key what it is partitioned on
   * @param interval the width of a partition
   * @param ahead how many intervals ahead are kept created
   * @param retention the declared retention
   * @param healthy whether the partition of the next interval is attached
   * @param lastPass the last pass, absent when none is recorded
   * @param partitionCount the attached partitions
   * @param bytes the sum of their sizes
   * @param rows the sum of the known estimates; absent when the table has partitions and none has one
   * @param partitionsWithoutEstimate the partitions that have no estimate
   * @param oldestFrom the start of the oldest partition
   * @param nextRemovalDue the earliest removal of any partition
   */
  public record AdminPartitionTableSummary(
      String name,
      @Nullable String database,
      AdminPartitionKey key,
      Interval interval,
      int ahead,
      AdminPartitionRetention retention,
      boolean healthy,
      @Nullable AdminPartitionLastPass lastPass,
      int partitionCount,
      long bytes,
      @Nullable Long rows,
      int partitionsWithoutEstimate,
      @Nullable Instant oldestFrom,
      @Nullable Instant nextRemovalDue) {}

  /**
   * Every declared table, in declaration order.
   *
   * @param tables the tables
   */
  public record AdminPartitionTableList(List<AdminPartitionTableSummary> tables) {}

  /**
   * One table: the declaration, the last pass, every partition (oldest first), the detached tables and the strays.
   *
   * @param name the table, schema-qualified
   * @param database the database instance; absent for the default
   * @param key what it is partitioned on
   * @param interval the width of a partition
   * @param ahead how many intervals ahead are kept created
   * @param retention the declared retention
   * @param healthy whether the partition of the next interval is attached
   * @param lastPass the last pass, absent when none is recorded
   * @param partitions the attached partitions, oldest first
   * @param detached plain tables carrying the module's detach mark that are not attached
   * @param strays attached partitions that are not this framework's names for the table
   * @param bytes the sum of the partitions' sizes
   * @param rows the sum of the known estimates; absent when the table has partitions and none has one
   * @param partitionsWithoutEstimate the partitions that have no estimate
   */
  public record AdminPartitionTable(
      String name,
      @Nullable String database,
      AdminPartitionKey key,
      Interval interval,
      int ahead,
      AdminPartitionRetention retention,
      boolean healthy,
      @Nullable AdminPartitionLastPass lastPass,
      List<AdminPartition> partitions,
      List<String> detached,
      List<String> strays,
      long bytes,
      @Nullable Long rows,
      int partitionsWithoutEstimate) {}

  /**
   * One archived partition.
   *
   * @param table the table, schema-qualified
   * @param partition the partition's table name
   * @param from the start of its range, absent when the name is not parsed
   * @param to the end of its range
   * @param objects objects written
   * @param rows rows archived
   * @param bytes bytes archived
   * @param archivedAt when it was archived
   * @param key the key of the first object in the store (the others sit beside it); not a credential
   */
  public record AdminPartitionArchive(
      String table,
      String partition,
      @Nullable Instant from,
      @Nullable Instant to,
      int objects,
      long rows,
      long bytes,
      Instant archivedAt,
      String key) {}

  /**
   * The table to run a pass for; {@code {}} runs every table.
   *
   * @param table the table as declared or schema-qualified; at most 200 characters
   */
  public record AdminPartitionPassBody(@Nullable String table) {}

  /**
   * What one table's pass did.
   *
   * @param table the table, schema-qualified
   * @param created partitions created
   * @param dropped partitions dropped
   * @param archived partitions archived
   * @param failed the steps that failed (the log has the cause)
   */
  public record AdminPartitionPassResult(String table, int created, int dropped, int archived, List<Step> failed) {}

  /**
   * The results of a manual pass, in declaration order.
   *
   * @param results one per table
   */
  public record AdminPartitionPassResults(List<AdminPartitionPassResult> results) {}

  /**
   * The window to create partitions for (RFC 3339 instants).
   *
   * @param from the start, required
   * @param to the end, required
   */
  public record AdminPartitionEnsureBody(@Nullable Instant from, @Nullable Instant to) {}

  /**
   * How many partitions an ensure created.
   *
   * @param created partitions created
   * @param alreadyDue partitions of the range that are already past retention, which the next pass removes
   */
  public record AdminPartitionEnsureResult(int created, int alreadyDue) {}

  // ---- the projection from a status to the wire ------------------------------------------------

  static @Nullable String databaseOf(String name) {
    return name.isEmpty() ? null : name;
  }

  static AdminPartitionKey keyOf(Key key) {
    return new AdminPartitionKey(
        key.kind(), key.column(), key.isId() ? key.prefix() : null, key.columnType().orElse(null));
  }

  static AdminPartitionRetention retentionOf(RetentionStatus r) {
    boolean keeps = r.kind() == RetentionKind.KEEP;
    return new AdminPartitionRetention(r.kind(), keeps ? null : r.after(), r.store().isEmpty() ? null : r.store());
  }

  static @Nullable AdminPartitionLastPass lastPassOf(java.util.Optional<LastPass> last) {
    if (last.isEmpty()) {
      return null;
    }
    LastPass l = last.get();
    return new AdminPartitionLastPass(
        l.endedAt(),
        l.ensure(),
        l.retention(),
        l.archive(),
        l.created(),
        l.dropped(),
        l.archived(),
        l.trigger().orElse(null),
        l.actor().filter(a -> !a.isEmpty()).orElse(null));
  }

  static AdminPartition partitionOf(PartitionStatus p) {
    ArchivedPartition a = p.archived().orElse(null);
    return new AdminPartition(
        p.name(),
        p.from(),
        p.to(),
        p.position(),
        p.bytes(),
        p.rows().isPresent() ? p.rows().getAsLong() : null,
        p.removalDue().orElse(null),
        a == null ? null : new AdminPartitionArchived(a.objects(), a.rows(), a.bytes(), a.archivedAt()));
  }

  static AdminPartitionTable tableOf(TableStatus st) {
    List<AdminPartition> partitions = new ArrayList<>(st.partitions().size());
    for (PartitionStatus p : st.partitions()) {
      partitions.add(partitionOf(p));
    }
    return new AdminPartitionTable(
        st.name(),
        databaseOf(st.database()),
        keyOf(st.key()),
        st.interval(),
        st.ahead(),
        retentionOf(st.retention()),
        st.healthy(),
        lastPassOf(st.lastPass()),
        List.copyOf(partitions),
        st.detached(),
        st.strays(),
        st.bytes(),
        st.rows().isPresent() ? st.rows().getAsLong() : null,
        st.partitionsWithoutEstimate());
  }

  static AdminPartitionTableSummary summaryOf(TableStatus st) {
    Instant oldest = null;
    Instant next = null;
    for (int i = 0; i < st.partitions().size(); i++) {
      PartitionStatus p = st.partitions().get(i);
      if (i == 0) { // oldest first
        oldest = p.from();
      }
      Instant due = p.removalDue().orElse(null);
      if (due != null && (next == null || due.isBefore(next))) {
        next = due;
      }
    }
    return new AdminPartitionTableSummary(
        st.name(),
        databaseOf(st.database()),
        keyOf(st.key()),
        st.interval(),
        st.ahead(),
        retentionOf(st.retention()),
        st.healthy(),
        lastPassOf(st.lastPass()),
        st.partitions().size(),
        st.bytes(),
        st.rows().isPresent() ? st.rows().getAsLong() : null,
        st.partitionsWithoutEstimate(),
        oldest,
        next);
  }

  static AdminPartitionArchive archiveOf(ArchiveRecord r) {
    return new AdminPartitionArchive(
        r.table(), r.partition(), r.from().orElse(null), r.to().orElse(null), r.objects(), r.rows(), r.bytes(), r.archivedAt(), r.key());
  }

  static AdminPartitionPassResult passResultOf(io.flowcatalyst.framework.partitions.PassResult r) {
    List<Step> failed = new ArrayList<>();
    for (io.flowcatalyst.framework.partitions.Step s : r.failed()) {
      failed.add(Step.valueOf(s.label()));
    }
    return new AdminPartitionPassResult(r.table(), r.created(), r.dropped(), r.archived(), List.copyOf(failed));
  }

  // ---- the operations ----------------------------------------------------------------------------

  /** A bound that must be given: Go's rule and wording. */
  private static final Rule<Instant> GIVEN =
      (value, violations) -> {
        if (value == null) {
          violations.add("NotNull", "must be given");
        }
      };

  static final Validator<AdminPartitionPassBody> PASS_RULES =
      Validator.of(AdminPartitionPassBody.class).field("table", AdminPartitionPassBody::table, maxLength(200));

  static final Validator<AdminPartitionEnsureBody> ENSURE_RULES =
      Validator.of(AdminPartitionEnsureBody.class)
          .field("from", AdminPartitionEnsureBody::from, GIVEN)
          .field("to", AdminPartitionEnsureBody::to, GIVEN);

  static final PathParam<String> TABLE = PathParam.string("table");
  static final QueryParam<Integer> LIMIT = QueryParam.integer("limit").defaultValue(50);
  static final QueryParam<@Nullable String> AFTER = QueryParam.string("after").optional();

  /** The section's operations for one application. */
  static final class Operations {
    static final String NOT_FOUND = "no declared table has this name";

    final Operation<NoBody, AdminPartitionTableList> list;
    final Operation<NoBody, AdminPartitionTable> get;
    final Operation<NoBody, Page<AdminPartitionArchive>> archives;
    final Operation<AdminPartitionPassBody, AdminPartitionPassResults> pass;
    final Operation<AdminPartitionEnsureBody, AdminPartitionEnsureResult> ensure;

    Operations(String view, String manage) {
      list =
          Operation.get("/api/admin/partitions", AdminPartitionTableList.class)
              .id("listAdminPartitions")
              .summary("The partitioned tables: declaration, health, last pass and totals")
              .tag("admin")
              .permission(view)
              .operatorsOnly()
              .build();
      get =
          Operation.get("/api/admin/partitions/{table}", AdminPartitionTable.class)
              .id("getAdminPartitionTable")
              .summary("One partitioned table with its partitions")
              .tag("admin")
              .path(TABLE)
              .permission(view)
              .operatorsOnly()
              .error(404, "PARTITION_TABLE_NOT_FOUND", NOT_FOUND)
              .build();
      archives =
          Operation.get("/api/admin/partitions/{table}/archives", new TypeRef<Page<AdminPartitionArchive>>() {})
              .id("listAdminPartitionArchives")
              .summary("The archived partitions of a table, newest first")
              .tag("admin")
              .path(TABLE)
              .query(LIMIT, AFTER)
              .permission(view)
              .operatorsOnly()
              .error(404, "PARTITION_TABLE_NOT_FOUND", NOT_FOUND)
              .build();
      pass =
          Operation.post("/api/admin/partitions/passes", PASS_RULES, AdminPartitionPassResults.class)
              .id("runAdminPartitionPass")
              .summary("Run the ensure-then-retention pass now, for one table or all")
              .tag("admin")
              .permission(manage)
              .operatorsOnly()
              .error(404, "PARTITION_TABLE_NOT_FOUND", NOT_FOUND)
              .error(409, "PARTITION_PASS_RUNNING", "another pass is running; a manual pass is refused, never queued")
              .build();
      ensure =
          Operation.post("/api/admin/partitions/{table}/ensure", ENSURE_RULES, AdminPartitionEnsureResult.class)
              .id("ensureAdminPartitions")
              .summary("Create the partitions that cover a range")
              .tag("admin")
              .path(TABLE)
              .permission(manage)
              .operatorsOnly()
              .error(404, "PARTITION_TABLE_NOT_FOUND", NOT_FOUND)
              .error(400, "PARTITION_RANGE_INVALID", "the range ends before it starts, or starts before 1970")
              .error(400, "PARTITION_RANGE_TOO_LARGE", "the range needs more partitions than one call creates")
              .error(409, "PARTITION_DETACHED_LEFTOVER", "a detached table has the name of a partition in the range")
              .build();
    }
  }
}
