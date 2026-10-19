package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.sdk.sync.DefinitionSyncException;
import io.flowcatalyst.sdk.sync.Definitions.DefinitionSet;
import io.flowcatalyst.sdk.sync.SyncOptions;
import io.flowcatalyst.sdk.sync.SyncOptions.SyncCategory;
import io.flowcatalyst.sdk.sync.SyncResult;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes the application's {@linkplain DefinitionCatalog definitions} to the platform with the
 * SDK's synchronizer. Idempotent: running it twice leaves the platform unchanged.
 *
 * <p>Categories are synced one at a time, in dependency order, and a failure in one does not stop
 * the next: {@link #run} attempts every category and then throws {@link SyncException} carrying the
 * full report. The sync never asks the platform to remove definitions that are no longer declared
 * (the SDK's {@code removeUnlisted} flag stays off); deleting is an operator action.
 *
 * <p>Order: roles (which carry the permissions), event types, connections, dispatch pools,
 * subscriptions (they refer to the three before them), processes, scheduled jobs, documentation.
 */
public final class DefinitionSync {

  private static final Logger LOG = LoggerFactory.getLogger(DefinitionSync.class);

  /** Whether {@link #run} writes to the platform. */
  public enum Mode {
    /** Send every category to the platform. */
    APPLY,
    /**
     * Send nothing: validate and report what would be sent. The platform offers no preview, so a
     * dry run cannot say what would be created, updated or deleted, and does not contact the
     * platform at all.
     */
    DRY_RUN
  }

  /** A category of definitions, synced by one platform call (or one per client scope). */
  public enum Category {
    /** Roles, carrying the permissions they grant. */
    ROLES,
    /** Event types. */
    EVENT_TYPES,
    /** Connections. */
    CONNECTIONS,
    /** Dispatch pools. */
    DISPATCH_POOLS,
    /** Subscriptions. */
    SUBSCRIPTIONS,
    /** Processes. */
    PROCESSES,
    /** Scheduled jobs. */
    SCHEDULED_JOBS,
    /** Documentation pages; the Java SDK cannot sync these yet, so they are always skipped. */
    DOCS
  }

  private record Step(
      Category category,
      List<String> codes,
      @Nullable SyncCategory sdk,
      @Nullable Function<SyncResult, SyncResult.Category> pick) {}

  private final PlatformClient client;
  private final DefinitionCatalog catalog;
  private final Supplier<JobTargets> jobTargets;

  DefinitionSync(PlatformClient client, DefinitionCatalog catalog, Supplier<JobTargets> jobTargets) {
    this.client = client;
    this.catalog = catalog;
    this.jobTargets = jobTargets;
  }

  /**
   * Syncs every category.
   *
   * @param mode {@link Mode#APPLY} to write, {@link Mode#DRY_RUN} to only report
   * @return one result per category
   * @throws SyncException when any category failed, after every category was attempted
   * @throws IllegalStateException when the flowcatalyst module is disabled
   */
  public SyncReport run(Mode mode) {
    if (!client.isEnabled()) {
      throw new IllegalStateException("the flowcatalyst module is disabled (flowcatalyst.enabled)");
    }
    warnAboutUnusedPermissions();
    DefinitionSet set = SdkMapping.toSet(client.application().value(), catalog, jobTargets.get());
    List<CategoryResult> results = new ArrayList<>();
    for (Step step : steps()) {
      CategoryResult result = mode == Mode.DRY_RUN ? dryRun(step) : apply(step, set);
      LOG.info("sync {}", result.describe());
      results.add(result);
    }
    SyncReport report = new SyncReport(mode, results);
    if (report.hasFailures()) {
      throw new SyncException(report);
    }
    return report;
  }

  private List<Step> steps() {
    return List.of(
        new Step(
            Category.ROLES,
            catalog.roles().stream().map(Definition.Role::name).toList(),
            SyncCategory.ROLES,
            SyncResult::roles),
        new Step(
            Category.EVENT_TYPES,
            catalog.eventTypes().stream().map(Definition.EventType::code).toList(),
            SyncCategory.EVENT_TYPES,
            SyncResult::eventTypes),
        new Step(
            Category.CONNECTIONS,
            catalog.connections().stream().map(Definition.Connection::code).toList(),
            SyncCategory.CONNECTIONS,
            SyncResult::connections),
        new Step(
            Category.DISPATCH_POOLS,
            catalog.dispatchPools().stream().map(Definition.DispatchPool::code).toList(),
            SyncCategory.DISPATCH_POOLS,
            SyncResult::dispatchPools),
        new Step(
            Category.SUBSCRIPTIONS,
            catalog.subscriptions().stream().map(Definition.Subscription::code).toList(),
            SyncCategory.SUBSCRIPTIONS,
            SyncResult::subscriptions),
        new Step(
            Category.PROCESSES,
            catalog.processes().stream().map(Definition.Process::code).toList(),
            SyncCategory.PROCESSES,
            SyncResult::processes),
        new Step(
            Category.SCHEDULED_JOBS,
            catalog.scheduledJobs().stream().map(Definition.ScheduledJob::code).toList(),
            SyncCategory.SCHEDULED_JOBS,
            SyncResult::scheduledJobs),
        new Step(
            Category.DOCS,
            catalog.docs().stream().map(Definition.Doc::slug).toList(),
            null,
            null));
  }

  private static CategoryResult dryRun(Step step) {
    if (step.codes().isEmpty()) {
      return new CategoryResult(step.category(), CategoryResult.Outcome.SKIPPED, 0, 0, 0, 0, List.of(), null);
    }
    if (step.sdk() == null) {
      return docsSkipped(step);
    }
    return new CategoryResult(
        step.category(), CategoryResult.Outcome.WOULD_SYNC, step.codes().size(), 0, 0, 0, step.codes(), null);
  }

  private CategoryResult apply(Step step, DefinitionSet set) {
    if (step.codes().isEmpty()) {
      return new CategoryResult(step.category(), CategoryResult.Outcome.SKIPPED, 0, 0, 0, 0, List.of(), null);
    }
    SyncCategory sdkCategory = step.sdk();
    Function<SyncResult, SyncResult.Category> pick = step.pick();
    if (sdkCategory == null || pick == null) {
      return docsSkipped(step);
    }
    Set<SyncCategory> skip = new HashSet<>(EnumSet.allOf(SyncCategory.class));
    skip.remove(sdkCategory);
    SyncOptions options = new SyncOptions(false, Set.copyOf(skip));
    try {
      return fromSdk(step, pick.apply(client.sdk().definitions().sync(set, options)));
    } catch (DefinitionSyncException e) {
      SyncResult partial = e.result();
      if (partial != null) {
        return fromSdk(step, pick.apply(partial));
      }
      return failed(step, e);
    } catch (RuntimeException e) {
      return failed(step, e);
    }
  }

  private static CategoryResult fromSdk(Step step, SyncResult.Category sdk) {
    return switch (sdk) {
      case SyncResult.Category.Synced s ->
          new CategoryResult(
              step.category(),
              CategoryResult.Outcome.SYNCED,
              step.codes().size(),
              s.created(),
              s.updated(),
              s.deleted(),
              s.syncedCodes(),
              null);
      case SyncResult.Category.Failed f ->
          new CategoryResult(
              step.category(),
              CategoryResult.Outcome.FAILED,
              step.codes().size(),
              f.created(),
              f.updated(),
              f.deleted(),
              f.syncedCodes(),
              f.error());
      case SyncResult.Category.Skipped s ->
          new CategoryResult(step.category(), CategoryResult.Outcome.SKIPPED, 0, 0, 0, 0, List.of(), null);
    };
  }

  private static CategoryResult failed(Step step, RuntimeException e) {
    String message = e.getMessage();
    return new CategoryResult(
        step.category(),
        CategoryResult.Outcome.FAILED,
        step.codes().size(),
        0,
        0,
        0,
        List.of(),
        message == null ? e.getClass().getSimpleName() : message);
  }

  private static CategoryResult docsSkipped(Step step) {
    String message =
        step.codes().size()
            + " documentation page(s) not sent: the Java SDK has no docs sync yet";
    LOG.warn("sync: {}", message);
    return new CategoryResult(step.category(), CategoryResult.Outcome.SKIPPED, 0, 0, 0, 0, List.of(), message);
  }

  /** Permissions reach the platform only through roles, so one no role grants is lost. */
  private void warnAboutUnusedPermissions() {
    Set<String> granted = new HashSet<>();
    for (Definition.Role role : catalog.roles()) {
      role.permissions().forEach(p -> granted.add(p.toLowerCase(java.util.Locale.ROOT)));
    }
    for (Definition.Permission p : catalog.permissions()) {
      if (!granted.contains(p.code())) {
        LOG.warn(
            "sync: permission '{}' is declared but no role grants it; permissions reach the"
                + " platform only through roles, so it is not sent",
            p.code());
      }
    }
  }
}
