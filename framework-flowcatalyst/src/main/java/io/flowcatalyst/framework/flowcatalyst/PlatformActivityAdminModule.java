package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.admin.Admin;
import io.flowcatalyst.framework.admin.AdminApi;
import io.flowcatalyst.framework.admin.AdminSection;
import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.flowcatalyst.PlatformActivityApi.AdminAttempt;
import io.flowcatalyst.framework.flowcatalyst.PlatformActivityApi.AdminContextEntry;
import io.flowcatalyst.framework.flowcatalyst.PlatformActivityApi.AdminDispatchJob;
import io.flowcatalyst.framework.flowcatalyst.PlatformActivityApi.AdminDispatchJobDetail;
import io.flowcatalyst.framework.flowcatalyst.PlatformActivityApi.AdminEvent;
import io.flowcatalyst.framework.flowcatalyst.PlatformActivityApi.AdminEventDetail;
import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Outcome;
import io.flowcatalyst.framework.http.Routes;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The admin <b>activity</b> section (M4e slice 4, admin contract section 12): the application's own
 * events and the dispatch jobs that deliver them, with each job's attempts, read-only, through the
 * application's service account after the framework has checked the signed-in user's permission {@code
 * {app}:admin:activity:view}. List it after {@code AdminModule} and {@code FlowCatalystModule}.
 *
 * <p>Only this application's records are returned, whatever the platform sends: an event whose type
 * does not start with {@code {app}:}, or a job whose code's first segment is not the application's,
 * is dropped from lists and is a {@code 404} by id (the attempts of a job are asked for only after
 * its code has been checked). A job is the application's because its code is the application's event
 * type; its <b>target</b> may be another application's subscriber, and is shown (without user info
 * or query) as the delivery target. Payloads and subscriber responses are shown, bounded (20 000
 * and 4 000 characters), to the holders of the permission.
 */
public final class PlatformActivityAdminModule implements Module {

  /** The module id. */
  public static final String ID = "admin-platform-activity";

  private static final int DATA_LIMIT = 20_000;
  private static final int BODY_LIMIT = 4_000;
  private static final Pattern STATUS = Pattern.compile("[A-Z_]{1,40}");

  private final ApplicationCode application;
  private final PlatformActivityApi.Operations operations;
  private final AdminSection activity;

  private PlatformActivityAdminModule(ApplicationCode application) {
    this.application = application;
    this.operations = new PlatformActivityApi.Operations(view(application));
    this.activity =
        AdminSection.of("activity", "Activity", "/admin/activity", view(application))
            .icon("i-lucide-activity")
            .description("This application's events and the dispatch jobs that deliver them.")
            .serving(operations.listEvents, operations.getEvent, operations.listJobs, operations.getJob);
  }

  /**
   * The module for an application.
   *
   * @param application the application code; it must equal {@code flowcatalyst.application}
   * @return the module
   */
  public static PlatformActivityAdminModule of(ApplicationCode application) {
    return new PlatformActivityAdminModule(application);
  }

  /**
   * The permission that opens the activity section.
   *
   * @param application the application code
   * @return {@code {app}:admin:activity:view}
   */
  public static String view(ApplicationCode application) {
    return application.value() + ":admin:activity:view";
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    Admin.section(app, activity);
  }

  @Override
  public void boot(App app) {
    PlatformClient client = app.get(PlatformClient.class);
    if (client.isEnabled() && !client.application().equals(application)) {
      throw new IllegalStateException(
          "PlatformActivityAdminModule is for application '"
              + application.value()
              + "' but flowcatalyst.application is '"
              + client.application().value()
              + "'");
    }
    Routes routes = app.get(Routes.class).forModule(ID);
    String own = application.value();

    routes.bind(
        operations.listEvents,
        r -> {
          int limit = AdminPlatform.limit(r.query(PlatformActivityApi.LIMIT));
          int offset = AdminPlatform.cursor(r.query(PlatformActivityApi.AFTER));
          String type = AdminPlatform.blankToNull(r.query(PlatformActivityApi.TYPE));
          String since = time(r.query(PlatformActivityApi.SINCE));
          String until = time(r.query(PlatformActivityApi.UNTIL));
          if (type != null && !type.startsWith(own + ":")) {
            // Another application's type matches nothing here; the platform is not asked.
            return Outcome.ok(new AdminApi.Page<AdminEvent>(List.of(), null));
          }
          List<PlatformAdminData.EventInfo> found =
              AdminPlatform.call(
                  client,
                  () ->
                      client.events(
                          new PlatformAdminData.EventQuery(
                              type,
                              AdminPlatform.blankToNull(r.query(PlatformActivityApi.SUBJECT)),
                              AdminPlatform.blankToNull(r.query(PlatformActivityApi.CORRELATION)),
                              AdminPlatform.blankToNull(r.query(PlatformActivityApi.CLIENT)),
                              since,
                              until,
                              limit + 1,
                              offset)),
                  null,
                  null);
          List<AdminEvent> items = new ArrayList<>();
          for (PlatformAdminData.EventInfo e : found.subList(0, Math.min(limit, found.size()))) {
            if (ownsEvent(own, e)) {
              items.add(event(e));
            }
          }
          return Outcome.ok(new AdminApi.Page<>(List.copyOf(items), found.size() > limit ? String.valueOf(offset + limit) : null));
        });

    routes.bind(
        operations.getEvent,
        r -> {
          String id = AdminPlatform.id(r.path(PlatformActivityApi.ID), "EVENT_NOT_FOUND", "there is no such event");
          PlatformAdminData.EventDetail detail = AdminPlatform.call(client, () -> client.event(id), "EVENT_NOT_FOUND", "there is no such event");
          if (!ownsEvent(own, detail.event())) {
            throw new HttpException(404, "EVENT_NOT_FOUND", "there is no such event");
          }
          List<PlatformAdminData.JobInfo> jobs = AdminPlatform.call(client, () -> client.dispatchJobsOfEvent(id), null, null);
          List<AdminDispatchJob> mine = new ArrayList<>();
          for (PlatformAdminData.JobInfo j : jobs) {
            if (ownsJob(own, j)) {
              mine.add(job(j));
            }
          }
          List<AdminContextEntry> context = new ArrayList<>();
          for (PlatformAdminData.ContextEntry c : detail.context()) {
            context.add(new AdminContextEntry(c.key(), c.value()));
          }
          String data = detail.data();
          boolean cut = data != null && data.length() > DATA_LIMIT;
          return Outcome.ok(
              new AdminEventDetail(
                  event(detail.event()),
                  data == null ? null : cut ? data.substring(0, DATA_LIMIT) : data,
                  cut,
                  List.copyOf(context),
                  detail.causationId(),
                  List.copyOf(mine)));
        });

    routes.bind(
        operations.listJobs,
        r -> {
          int limit = AdminPlatform.limit(r.query(PlatformActivityApi.LIMIT));
          int offset = AdminPlatform.cursor(r.query(PlatformActivityApi.AFTER));
          String code = AdminPlatform.blankToNull(r.query(PlatformActivityApi.CODE));
          String status = AdminPlatform.blankToNull(r.query(PlatformActivityApi.STATUS));
          if (status != null && !STATUS.matcher(status).matches()) {
            throw new HttpException(400, "INVALID_STATUS", "the status is an upper-case word, such as COMPLETED");
          }
          String since = time(r.query(PlatformActivityApi.SINCE));
          String until = time(r.query(PlatformActivityApi.UNTIL));
          if (code != null && !code.startsWith(own + ":")) {
            return Outcome.ok(new AdminApi.Page<AdminDispatchJob>(List.of(), null));
          }
          List<PlatformAdminData.JobInfo> found =
              AdminPlatform.call(
                  client,
                  () ->
                      client.dispatchJobs(
                          new PlatformAdminData.JobQuery(
                              status == null ? List.of() : List.of(status),
                              code,
                              AdminPlatform.blankToNull(r.query(PlatformActivityApi.CLIENT)),
                              AdminPlatform.blankToNull(r.query(PlatformActivityApi.MESSAGE_GROUP)),
                              since,
                              until,
                              limit + 1,
                              offset)),
                  null,
                  null);
          List<AdminDispatchJob> items = new ArrayList<>();
          for (PlatformAdminData.JobInfo j : found.subList(0, Math.min(limit, found.size()))) {
            if (ownsJob(own, j)) {
              items.add(job(j));
            }
          }
          return Outcome.ok(new AdminApi.Page<>(List.copyOf(items), found.size() > limit ? String.valueOf(offset + limit) : null));
        });

    routes.bind(
        operations.getJob,
        r -> {
          String id = AdminPlatform.id(r.path(PlatformActivityApi.ID), "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job");
          PlatformAdminData.JobInfo found =
              AdminPlatform.call(client, () -> client.dispatchJob(id), "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job");
          if (!ownsJob(own, found)) {
            // Not even its attempts are asked for.
            throw new HttpException(404, "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job");
          }
          List<PlatformAdminData.AttemptInfo> attempts =
              AdminPlatform.call(client, () -> client.dispatchJobAttempts(id), "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job");
          List<AdminAttempt> out = new ArrayList<>();
          for (PlatformAdminData.AttemptInfo a : attempts) {
            String body = a.responseBody();
            boolean cut = body != null && body.length() > BODY_LIMIT;
            out.add(
                new AdminAttempt(
                    a.attemptNumber(),
                    a.attemptedAt(),
                    a.completedAt(),
                    a.durationMillis(),
                    a.responseCode(),
                    body == null ? null : cut ? body.substring(0, BODY_LIMIT) : body,
                    cut,
                    a.success(),
                    a.errorMessage(),
                    a.errorType()));
          }
          return Outcome.ok(
              new AdminDispatchJobDetail(job(found), found.protocol(), found.timeoutSeconds(), found.maxRetries(), found.retryStrategy(), found.lastError(), List.copyOf(out)));
        });
  }

  private static boolean ownsEvent(String own, PlatformAdminData.EventInfo e) {
    return e.type().startsWith(own + ":");
  }

  private static boolean ownsJob(String own, PlatformAdminData.JobInfo j) {
    int colon = j.code().indexOf(':');
    return (colon < 0 ? j.code() : j.code().substring(0, colon)).equals(own);
  }

  private static AdminEvent event(PlatformAdminData.EventInfo e) {
    return new AdminEvent(e.id(), e.type(), e.source(), e.subject(), e.time(), e.messageGroup(), e.correlationId(), e.clientId());
  }

  private static AdminDispatchJob job(PlatformAdminData.JobInfo j) {
    return new AdminDispatchJob(
        j.id(),
        j.eventId(),
        j.subscriptionId(),
        j.clientId(),
        j.code(),
        j.status(),
        j.kind(),
        withoutSecrets(j.targetUrl()),
        j.mode(),
        j.messageGroup(),
        j.descriptor(),
        j.createdAt(),
        j.lastAttemptAt(),
        j.completedAt(),
        j.attemptCount());
  }

  /** The target URL without user info, query or fragment (they often carry the subscriber's credential). */
  static String withoutSecrets(String url) {
    int cut = url.length();
    for (char c : new char[] {'?', '#'}) {
      int at = url.indexOf(c);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    String base = url.substring(0, cut);
    int scheme = base.indexOf("://");
    if (scheme >= 0) {
      int at = base.indexOf('@', scheme + 3);
      int slash = base.indexOf('/', scheme + 3);
      if (at >= 0 && (slash < 0 || at < slash)) {
        base = base.substring(0, scheme + 3) + base.substring(at + 1);
      }
    }
    return base;
  }

  private static @Nullable String time(@Nullable String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      OffsetDateTime.parse(text);
      return text;
    } catch (DateTimeParseException e) {
      throw new HttpException(400, "INVALID_TIMESTAMP", "since and until are RFC 3339 times, such as 2026-10-16T08:00:00Z");
    }
  }
}
