package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.context.PerClient;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.FlagSlaBreach;
import io.flowcatalyst.framework.flowcatalyst.JobResult;
import io.flowcatalyst.framework.flowcatalyst.ScheduledJobFiring;
import io.flowcatalyst.framework.flowcatalyst.ScheduledJobHandler;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.time.InstantSource;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scheduled job {@link TicketsDefinitionsModule#SLA_SWEEP}: finds the clients that have tickets
 * that ran out of time, then, one client at a time with that client's own context ({@link
 * PerClient#each}), flags each ticket with the {@code flag-sla-breach} use case as the application's
 * service principal. The framework holds the overlap lock
 * ({@code scheduled-job:sla-sweep}, from the default lock store) around the whole run, so one replica
 * sweeps at a time; the use case is idempotent anyway.
 *
 * <p>A firing sweeps in batches until nothing new is found. A ticket the use case fails on (a
 * database error, say) would be found again, so each ticket is tried once per firing and the firing
 * reports a failure at the end; the next firing tries again.
 */
public final class SlaSweep implements ScheduledJobHandler {

  private static final Logger LOG = LoggerFactory.getLogger(SlaSweep.class);

  /** Tickets looked at per query. */
  static final int BATCH = 200;

  private final Database database;
  private final UseCases useCases;
  private final TicketUseCases tickets;
  private final InstantSource clock;

  /**
   * Creates the handler.
   *
   * @param database the database
   * @param useCases the runner
   * @param tickets the ticket use cases
   * @param clock the time of the application
   */
  public SlaSweep(Database database, UseCases useCases, TicketUseCases tickets, InstantSource clock) {
    this.database = database;
    this.useCases = useCases;
    this.tickets = tickets;
    this.clock = clock;
  }

  @Override
  public JobResult run(Context ctx, ScheduledJobFiring firing) throws Exception {
    // The firing's context is the platform's (_platform): the sweep acts for one client at a time,
    // each with that client's own context, so the queries are scoped and the use case authorizes
    // the service principal as it does for any caller.
    List<ClientId> clients = TicketQueries.overdueClients(database, clock.instant());
    int flagged = 0;
    int skipped = 0;
    int failed = 0;
    for (Map.Entry<ClientId, PerClient.Outcome<int[]>> entry : PerClient.each(ctx, clients, this::sweepClient).entrySet()) {
      switch (entry.getValue()) {
        case PerClient.Outcome.Done<int[]> done -> {
          flagged += done.value()[0];
          skipped += done.value()[1];
          failed += done.value()[2];
        }
        case PerClient.Outcome.Failed<int[]> broke -> {
          failed++;
          LOG.error("sweeping client {} failed", entry.getKey(), broke.cause());
        }
      }
    }
    firing.log("flagged " + flagged + ", skipped " + skipped + ", failed " + failed);
    if (failed > 0) {
      return JobResult.failure(failed + " tickets could not be flagged");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("flagged", flagged);
    result.put("skipped", skipped);
    return JobResult.of(result);
  }

  /**
   * One client's sweep, in batches until nothing new is found. A ticket the use case fails on would
   * be found again, so each ticket is tried once.
   *
   * @return {flagged, skipped, failed}
   */
  private int[] sweepClient(Context forClient) throws Exception {
    Set<TicketId> tried = new HashSet<>();
    int flagged = 0;
    int skipped = 0;
    int failed = 0;
    while (!Thread.currentThread().isInterrupted()) {
      List<TicketQueries.Overdue> batch = TicketQueries.overdueFor(database, forClient.client(), clock.instant(), BATCH);
      boolean progress = false;
      for (TicketQueries.Overdue overdue : batch) {
        if (!tried.add(overdue.id())) {
          continue;
        }
        progress = true;
        switch (useCases.run(tickets.flagSlaBreach(), forClient, new FlagSlaBreach(overdue.id().value()))) {
          case UseCaseResult.Committed<?> done -> flagged++;
          case UseCaseResult.Failed<?> refused -> {
            // The ticket was resolved or flagged since the query: nothing to do. Anything else is a failure.
            if ("NOT_OVERDUE".equals(refused.error().code()) || "TICKET_NOT_FOUND".equals(refused.error().code())) {
              skipped++;
            } else {
              failed++;
              LOG.error("flagging ticket {} failed: {} {}", overdue.id(), refused.error().kind(), refused.error().code());
            }
          }
        }
      }
      if (!progress) {
        break;
      }
    }
    return new int[] {flagged, skipped, failed};
  }
}
