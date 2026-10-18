package io.flowcatalyst.framework.examples.ticketstrial;

import io.flowcatalyst.framework.context.Context;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.AddComment;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.AssignTicket;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.ChangeStatus;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.OpenTicket;
import io.flowcatalyst.framework.examples.ticketstrial.Commands.SetSlaPolicy;
import io.flowcatalyst.framework.examples.ticketstrial.TicketsApi.Page;
import io.flowcatalyst.framework.examples.ticketstrial.TicketSettings.SlaMinutes;
import io.flowcatalyst.framework.examples.ticketstrial.TicketsApi.SlaPolicyView;
import io.flowcatalyst.framework.settings.ClientSettings;
import io.flowcatalyst.framework.settings.ClientSettingsRepository;
import io.flowcatalyst.framework.examples.ticketstrial.TicketsApi.TicketSummaryView;
import io.flowcatalyst.framework.examples.ticketstrial.TicketsApi.TicketView;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.RouteOptions;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.openapi.OpenApi;
import io.flowcatalyst.framework.http.ratelimit.KeyResolvers;
import io.flowcatalyst.framework.http.ratelimit.MemoryRateLimiter;
import io.flowcatalyst.framework.http.ratelimit.RateLimit;
import io.flowcatalyst.framework.http.ratelimit.RateLimitPolicy;
import io.flowcatalyst.framework.usecase.DomainEvent;
import io.flowcatalyst.framework.usecase.UseCaseError;
import io.flowcatalyst.framework.usecase.UseCaseResult;
import io.flowcatalyst.framework.usecase.UseCases;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;

/**
 * Declares the tickets API in {@code register} (so {@code openapi} writes the document with no
 * database) and binds it in {@code boot}. The use cases decide; a failed one is answered with its
 * {@code UseCaseError}, which is a {@code ServiceError}. Commenting is rate limited per person.
 */
public final class TicketsApiModule implements Module {

  /** The module id. */
  public static final String ID = "tickets-api";

  /** Comments per person: thirty a minute, a burst of ten. */
  public static final RateLimitPolicy COMMENT_LIMIT = new RateLimitPolicy(30, Duration.ofMinutes(1), 10);

  private final RateLimitPolicy commentLimit;

  /** The module with the usual comment limit. */
  public TicketsApiModule() {
    this(COMMENT_LIMIT);
  }

  /**
   * The module with another comment limit (a test uses a small one).
   *
   * @param commentLimit how often one person may comment
   */
  public TicketsApiModule(RateLimitPolicy commentLimit) {
    this.commentLimit = commentLimit;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    OpenApi.declare(
        app,
        TicketsApi.LIST,
        TicketsApi.GET,
        TicketsApi.OPEN,
        TicketsApi.ASSIGN,
        TicketsApi.TRANSITION,
        TicketsApi.COMMENT,
        TicketsApi.GET_SLA,
        TicketsApi.SET_SLA);
    OpenApi.info(app, "tickets-trial", "1");
  }

  @Override
  public void boot(App app) {
    UseCases useCases = app.get(UseCases.class);
    TicketUseCases tickets = app.get(TicketUseCases.class);
    InstantSource clock = app.get(InstantSource.class);
    Database db = app.get(Databases.class).defaultInstance();
    TicketRepository repository = new TicketRepository();
    ClientSettingsRepository settings = app.get(ClientSettingsRepository.class);
    Routes routes = app.get(Routes.class).forModule(ID);

    routes.bind(
        TicketsApi.LIST,
        r -> {
          TicketQueries.Page page =
              TicketQueries.list(
                  db,
                  r.context().client(),
                  r.query(TicketsApi.STATUS),
                  r.query(TicketsApi.ASSIGNEE),
                  r.query(TicketsApi.REQUESTER),
                  r.query(TicketsApi.AFTER),
                  r.query(TicketsApi.LIMIT));
          Instant now = clock.instant();
          List<TicketSummaryView> items = page.items().stream().map(s -> TicketSummaryView.of(s, now)).toList();
          return Outcome.ok(new Page<>(items, page.nextCursor()));
        });

    routes.bind(TicketsApi.GET, r -> view(repository, db, clock, r.context(), r.path(TicketsApi.TICKET_ID)));

    routes.bind(
        TicketsApi.OPEN,
        r -> {
          Context context = r.context();
          Outcome<TicketView> out =
              then(
                  useCases.run(
                      tickets.openTicket(),
                      context,
                      new OpenTicket(r.body().subject(), r.body().description(), r.body().priority())),
                  opened -> view(repository, db, clock, context, opened.ticketId()));
          return out instanceof Outcome.Ok<TicketView> ok ? ok.header("Location", "/api/tickets/" + ok.value().id()) : out;
        });

    routes.bind(
        TicketsApi.ASSIGN,
        r -> {
          String id = r.path(TicketsApi.TICKET_ID);
          return then(
              useCases.run(tickets.assignTicket(), r.context(), new AssignTicket(id, r.body().assigneeId())),
              done -> view(repository, db, clock, r.context(), id));
        });

    routes.bind(
        TicketsApi.TRANSITION,
        r -> {
          String id = r.path(TicketsApi.TICKET_ID);
          return then(
              useCases.run(tickets.changeStatus(), r.context(), new ChangeStatus(id, r.body().action(), r.body().note())),
              done -> view(repository, db, clock, r.context(), id));
        });

    // The limiter is built here, in code, with its policy as a constructor argument: a limiter is a
    // thing an application can have many of (config-and-code ruling), so it is not a setting.
    routes.bind(
        TicketsApi.COMMENT,
        r -> {
          String id = r.path(TicketsApi.TICKET_ID);
          return then(
              useCases.run(tickets.addComment(), r.context(), new AddComment(id, r.body().body())),
              done -> view(repository, db, clock, r.context(), id));
        },
        RouteOptions.none()
            .with(RateLimit.by(MemoryRateLimiter.create("ticket-comments", commentLimit), KeyResolvers.principal()).build()));

    routes.bind(TicketsApi.GET_SLA, r -> Outcome.ok(slaView(settings, db, r.context())));

    routes.bind(
        TicketsApi.SET_SLA,
        r -> {
          TicketsApi.SetSlaPolicyBody b = r.body();
          return then(
              useCases.run(
                  tickets.setSlaPolicy(), r.context(), new SetSlaPolicy(b.lowMinutes(), b.normalMinutes(), b.highMinutes(), b.urgentMinutes())),
              done -> Outcome.ok(slaView(settings, db, r.context())));
        });
  }

  private static SlaPolicyView slaView(ClientSettingsRepository settings, Database db, Context context) throws SQLException {
    ClientSettings own = settings.forClient(db, context.client());
    SlaMinutes sla = own.get(TicketSettings.SLA);
    return new SlaPolicyView(sla.low(), sla.normal(), sla.high(), sla.urgent(), own.isSet(TicketSettings.SLA), own.version());
  }

  private static Outcome<TicketView> view(TicketRepository repository, Database db, InstantSource clock, Context context, String id)
      throws SQLException {
    return repository
        .find(db, context.client(), new TicketId(id))
        .<Outcome<TicketView>>map(t -> Outcome.ok(TicketView.of(t, clock.instant())))
        .orElseGet(() -> Outcome.failed(UseCaseError.notFound("TICKET_NOT_FOUND", "There is no such ticket.")));
  }

  /** What to answer once a use case committed. */
  private interface Step<E, R> {
    Outcome<R> apply(E event) throws Exception;
  }

  /** A committed use case becomes the next outcome; a failed one is answered with its error. */
  private static <E extends DomainEvent, R> Outcome<R> then(UseCaseResult<E> result, Step<E, R> next) throws Exception {
    return switch (result) {
      case UseCaseResult.Committed<E> c -> next.apply(c.event());
      case UseCaseResult.Failed<E> f -> Outcome.failed(f.error());
    };
  }
}
