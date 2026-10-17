package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Background;
import io.flowcatalyst.framework.core.LoopOptions;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.flowcatalyst.Receivers;
import io.flowcatalyst.framework.httpclient.module.ServiceClients;
import io.flowcatalyst.framework.locks.LockGate;
import io.flowcatalyst.framework.locks.Locks;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.secrets.SecretResolver;
import io.flowcatalyst.framework.usecase.UseCases;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the platform and the clock call into: the dispatch receiver that notifies an assignee,
 * the scheduled-job receiver that runs the SLA sweep, and a loop that publishes how many tickets are
 * active and overdue as gauges. Registered in {@code boot}, which is when {@link Receivers} is open.
 */
public final class TicketsReceiversModule implements Module {

  /** The module id. */
  public static final String ID = "tickets-receivers";

  /** The gauge of tickets somebody still has to resolve, across every client. */
  public static final String ACTIVE_GAUGE = "tickets_active_count";

  /** The gauge of active tickets the sweep has flagged, across every client. */
  public static final String OVERDUE_GAUGE = "tickets_overdue_count";

  /** How often the gauges are refreshed. */
  static final Duration GAUGE_INTERVAL = Duration.ofSeconds(30);

  private final Duration gaugeInterval;

  /** The module with the usual refresh interval. */
  public TicketsReceiversModule() {
    this(GAUGE_INTERVAL);
  }

  /**
   * The module with another refresh interval (a test uses a short one).
   *
   * @param gaugeInterval how often the gauges are refreshed
   */
  public TicketsReceiversModule(Duration gaugeInterval) {
    this.gaugeInterval = gaugeInterval;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void boot(App app) {
    Database db = app.get(Databases.class).defaultInstance();
    Receivers receivers = app.get(Receivers.class);
    receivers.onDispatch(
        TicketEvents.ASSIGNED.type(), new NotifyAssignee(app.get(ServiceClients.class), app.get(SecretResolver.class)));
    receivers.onScheduledJob(
        TicketsDefinitionsModule.SLA_SWEEP,
        new SlaSweep(db, app.get(UseCases.class), app.get(TicketUseCases.class), app.get(InstantSource.class)));

    // One replica refreshes the gauges at a time; each replica exports what the last run found.
    Metrics metrics = app.get(Metrics.class);
    AtomicLong active = new AtomicLong();
    AtomicLong overdue = new AtomicLong();
    metrics.gauge(ACTIVE_GAUGE, "Tickets somebody still has to resolve, across every client.", active::doubleValue);
    metrics.gauge(OVERDUE_GAUGE, "Active tickets the SLA sweep flagged, across every client.", overdue::doubleValue);
    app.get(Background.class)
        .loop(
            "ticket-gauges",
            LoopOptions.every(gaugeInterval).gatedBy(LockGate.service(app.get(Locks.class), "ticket-gauges", Duration.ofMinutes(1))),
            tick -> {
              long[] totals = TicketQueries.totals(db);
              active.set(totals[0]);
              overdue.set(totals[1]);
            });
  }
}
