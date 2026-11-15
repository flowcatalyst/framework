package io.flowcatalyst.framework.partitions.admin;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminApi.Page;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.partitions.ArchiveRecord;
import io.flowcatalyst.framework.partitions.EnsureResult;
import io.flowcatalyst.framework.partitions.PartitionRefusedException;
import io.flowcatalyst.framework.partitions.Partitions;
import io.flowcatalyst.framework.partitions.TableStatus;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionArchive;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionEnsureBody;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionEnsureResult;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionPassBody;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionPassResult;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionPassResults;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionTable;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionTableList;
import io.flowcatalyst.framework.partitions.admin.PartitionsAdminApi.AdminPartitionTableSummary;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The admin <b>partitions</b> section (partitions contract section 13): the state of every declared
 * partitioned table, its partitions and archives, and two operator actions (run a pass now, create
 * the partitions of a range). It is headless: operations, permissions and a navigation entry; the
 * application draws its own page.
 *
 * <p>Id {@code partitions}, path {@code /admin/partitions}, permission {@code
 * {app}:admin:partitions:view} (the three reads) and {@code {app}:admin:partitions:manage} (the two
 * actions). The section is <b>operators only</b> (admin contract section 3a): besides its
 * permission every operation requires a caller whose tier is {@code ANCHOR}. A partition holds the
 * rows of every client, so never give these permissions to a role that client administrators may
 * assign (a client-managed role): the FlowCatalyst module refuses that at start-up.
 *
 * <p>List it after {@code AdminModule} and the partitions module; it works in an ordinary admin
 * area and in one declared {@code AdminModule.Option.OPERATORS_ONLY}.
 *
 * <pre>{@code
 * App.builder()
 *     .module(AdminModule.of(app))
 *     .module(PartitionsModule.builder().table(...).build())
 *     .module(PartitionsAdminModule.of(app))
 * }</pre>
 */
public final class PartitionsAdminModule implements Module {

  /** The module id. */
  public static final String ID = "admin-partitions";

  /** The id of the section. */
  public static final String SECTION_ID = "partitions";

  private static final String ABSENT_TABLE_PREFIX = "no declared table is named '";

  private final PartitionsAdminApi.Operations operations;
  private final AdminSection section;

  private PartitionsAdminModule(ApplicationCode application) {
    this.operations = new PartitionsAdminApi.Operations(view(application), manage(application));
    this.section =
        AdminSection.of(SECTION_ID, "Partitions", "/admin/partitions", view(application))
            .icon("i-lucide-database")
            .description("The partitioned tables: their partitions, retention and archives, for platform operators.")
            .operatorsOnly()
            .serving(operations.list, operations.get, operations.archives, operations.pass, operations.ensure);
  }

  /**
   * The module for an application.
   *
   * @param application the application code; every permission starts with it
   * @return the module
   */
  public static PartitionsAdminModule of(ApplicationCode application) {
    return new PartitionsAdminModule(application);
  }

  /**
   * The permission that opens the section and reads it: {@code {app}:admin:partitions:view}.
   *
   * @param application the application code
   * @return the permission
   */
  public static String view(ApplicationCode application) {
    return application.value() + ":admin:partitions:view";
  }

  /**
   * The permission of the two actions (run a pass, ensure a range): {@code
   * {app}:admin:partitions:manage}.
   *
   * @param application the application code
   * @return the permission
   */
  public static String manage(ApplicationCode application) {
    return application.value() + ":admin:partitions:manage";
  }

  /**
   * The declared section, without handlers: what the registry, the OpenAPI document and a
   * permission check see. A test of the operators-only rules uses it without a database.
   *
   * @param application the application code
   * @return the section
   */
  public static AdminSection section(ApplicationCode application) {
    return new PartitionsAdminModule(application).section;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, section);
  }

  @Override
  public void boot(App app) {
    Partitions partitions = app.get(Partitions.class);
    Routes routes = app.get(Routes.class).forModule(ID);

    routes.bind(
        operations.list,
        r -> {
          List<AdminPartitionTableSummary> tables = new ArrayList<>();
          for (TableStatus st : partitions.status()) {
            tables.add(PartitionsAdminApi.summaryOf(st));
          }
          return Outcome.ok(new AdminPartitionTableList(List.copyOf(tables)));
        });

    routes.bind(
        operations.get,
        r -> {
          String table = r.path(PartitionsAdminApi.TABLE);
          AdminPartitionTable found =
              partitions.tableStatus(table).map(PartitionsAdminApi::tableOf).orElseThrow(() -> notFound(table));
          return Outcome.ok(found);
        });

    routes.bind(
        operations.archives,
        r -> {
          String table = r.path(PartitionsAdminApi.TABLE);
          int limit = Math.max(1, Math.min(Partitions.MAX_ARCHIVE_PAGE, r.query(PartitionsAdminApi.LIMIT)));
          String after = r.query(PartitionsAdminApi.AFTER);
          String from = after == null ? "" : after;
          List<ArchiveRecord> records;
          try {
            // one more than the page: the extra record says there is a next page
            records = partitions.archives(table, from, limit + 1);
          } catch (PartitionRefusedException e) {
            throw refused(e, table);
          }
          boolean more = records.size() > limit;
          if (!more && records.size() == limit && limit == Partitions.MAX_ARCHIVE_PAGE) {
            // the module serves at most one page of 200, so limit + 1 was cut to 200: ask whether another follows
            more = !partitions.archives(table, records.get(limit - 1).partition(), 1).isEmpty();
          }
          List<AdminPartitionArchive> items = new ArrayList<>();
          for (int i = 0; i < Math.min(limit, records.size()); i++) {
            items.add(PartitionsAdminApi.archiveOf(records.get(i)));
          }
          String cursor = more ? records.get(limit - 1).partition() : null;
          return Outcome.ok(new Page<>(List.copyOf(items), cursor));
        });

    routes.bind(
        operations.pass,
        r -> {
          AdminPartitionPassBody body = r.body();
          String table = body.table() == null ? "" : body.table();
          List<io.flowcatalyst.framework.partitions.PassResult> results;
          try {
            results = partitions.runPass(r.context(), table);
          } catch (PartitionRefusedException e) {
            throw refused(e, table);
          }
          List<AdminPartitionPassResult> out = new ArrayList<>();
          for (io.flowcatalyst.framework.partitions.PassResult res : results) {
            out.add(PartitionsAdminApi.passResultOf(res));
          }
          return Outcome.ok(new AdminPartitionPassResults(List.copyOf(out)));
        });

    routes.bind(
        operations.ensure,
        r -> {
          String table = r.path(PartitionsAdminApi.TABLE);
          AdminPartitionEnsureBody body = r.body();
          // the validator has made both bounds present
          java.time.Instant from = java.util.Objects.requireNonNull(body.from());
          java.time.Instant to = java.util.Objects.requireNonNull(body.to());
          EnsureResult res;
          try {
            res = partitions.ensureAs(r.context(), table, from, to);
          } catch (PartitionRefusedException e) {
            throw refused(e, table);
          }
          return Outcome.ok(new AdminPartitionEnsureResult(res.created(), res.alreadyDue()));
        });
  }

  private static HttpException notFound(String table) {
    return new HttpException(404, "PARTITION_TABLE_NOT_FOUND", ABSENT_TABLE_PREFIX + table + "'");
  }

  /** The refusals of the partitions module as the section's errors (the messages are Go's). */
  private static HttpException refused(PartitionRefusedException e, String table) {
    return switch (e.reason()) {
      case TABLE_NOT_DECLARED -> notFound(table);
      case PASS_RUNNING -> new HttpException(409, "PARTITION_PASS_RUNNING", "another pass is running; try again when it has ended");
      case RANGE_INVALID -> new HttpException(400, "PARTITION_RANGE_INVALID", withoutPrefix(e, "range is invalid: "));
      case RANGE_TOO_LARGE -> new HttpException(400, "PARTITION_RANGE_TOO_LARGE", withoutPrefix(e, "range needs too many partitions: "));
      case DETACHED_LEFTOVER ->
          new HttpException(409, "PARTITION_DETACHED_LEFTOVER", withoutPrefix(e, "a detached table has the name of a partition: "));
    };
  }

  /** The refusal's own text without the text of its kind ("range is invalid: to is before from" is "to is before from"). */
  private static String withoutPrefix(PartitionRefusedException e, String prefix) {
    @Nullable String text = e.getMessage();
    if (text == null) {
      return "";
    }
    return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
  }
}
