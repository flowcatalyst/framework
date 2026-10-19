package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.ApplicationCode;
import io.flowcatalyst.framework.core.Problem;
import io.flowcatalyst.framework.core.StartupException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Everything the modules declared with {@link Definitions}, validated and frozen at start-up.
 *
 * <p>Validation needs {@code flowcatalyst.application}, which cannot be read during
 * {@code register}, so it runs when services are built (lifecycle step 3), before any {@code boot}.
 * It collects every problem and fails start-up with one {@link StartupException}; each problem
 * names the contributing module and the offending code.
 *
 * <p>When the flowcatalyst module is disabled nothing is validated and the catalog is empty.
 */
public final class DefinitionCatalog {

  private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9_-]*");
  private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_-]*");
  private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]*");

  private static final DefinitionCatalog EMPTY =
      new DefinitionCatalog(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

  private final List<Definition.EventType> eventTypes;
  private final List<Definition.Permission> permissions;
  private final List<Definition.Role> roles;
  private final List<Definition.Connection> connections;
  private final List<Definition.Subscription> subscriptions;
  private final List<Definition.DispatchPool> dispatchPools;
  private final List<Definition.ScheduledJob> scheduledJobs;
  private final List<Definition.Process> processes;
  private final List<Definition.Doc> docs;

  private DefinitionCatalog(
      List<Definition.EventType> eventTypes,
      List<Definition.Permission> permissions,
      List<Definition.Role> roles,
      List<Definition.Connection> connections,
      List<Definition.Subscription> subscriptions,
      List<Definition.DispatchPool> dispatchPools,
      List<Definition.ScheduledJob> scheduledJobs,
      List<Definition.Process> processes,
      List<Definition.Doc> docs) {
    this.eventTypes = eventTypes;
    this.permissions = permissions;
    this.roles = roles;
    this.connections = connections;
    this.subscriptions = subscriptions;
    this.dispatchPools = dispatchPools;
    this.scheduledJobs = scheduledJobs;
    this.processes = processes;
    this.docs = docs;
  }

  static DefinitionCatalog empty() {
    return EMPTY;
  }

  /**
   * Validates contributions and builds the catalog.
   *
   * @param contributions what modules declared, in registration order
   * @param application the application code every code must start with
   * @param subscriptionTargetBase whether path-style subscription targets can be resolved
   * @return the catalog
   * @throws StartupException listing every problem found
   */
  static DefinitionCatalog build(
      List<Definitions.Contribution> contributions,
      ApplicationCode application,
      boolean subscriptionTargetBase) {
    Validation v = new Validation(application.value(), subscriptionTargetBase);
    for (Definitions.Contribution c : contributions) {
      v.check(c.module(), c.definition());
    }
    if (!v.problems.isEmpty()) {
      throw new StartupException(v.problems);
    }
    return new DefinitionCatalog(
        List.copyOf(v.eventTypes.values()),
        List.copyOf(v.permissions.values()),
        List.copyOf(v.roles.values()),
        List.copyOf(v.connections.values()),
        List.copyOf(v.subscriptions.values()),
        List.copyOf(v.dispatchPools.values()),
        List.copyOf(v.scheduledJobs.values()),
        List.copyOf(v.processes.values()),
        List.copyOf(v.docs.values()));
  }

  /**
   * The declared event types.
   *
   * @return in declaration order
   */
  public List<Definition.EventType> eventTypes() {
    return eventTypes;
  }

  /**
   * The declared permissions.
   *
   * @return in declaration order
   */
  public List<Definition.Permission> permissions() {
    return permissions;
  }

  /**
   * The declared roles.
   *
   * @return in declaration order
   */
  public List<Definition.Role> roles() {
    return roles;
  }

  /**
   * The declared connections.
   *
   * @return in declaration order
   */
  public List<Definition.Connection> connections() {
    return connections;
  }

  /**
   * The declared subscriptions.
   *
   * @return in declaration order
   */
  public List<Definition.Subscription> subscriptions() {
    return subscriptions;
  }

  /**
   * The declared dispatch pools.
   *
   * @return in declaration order
   */
  public List<Definition.DispatchPool> dispatchPools() {
    return dispatchPools;
  }

  /**
   * The declared scheduled jobs.
   *
   * @return in declaration order
   */
  public List<Definition.ScheduledJob> scheduledJobs() {
    return scheduledJobs;
  }

  /**
   * The declared processes.
   *
   * @return in declaration order
   */
  public List<Definition.Process> processes() {
    return processes;
  }

  /**
   * The declared documentation pages.
   *
   * @return in declaration order
   */
  public List<Definition.Doc> docs() {
    return docs;
  }

  /** Checks one definition at a time and remembers the first contributor of each code. */
  private static final class Validation {
    final String app;
    final boolean targetBase;
    final List<Problem> problems = new ArrayList<>();
    final Map<String, Definition.EventType> eventTypes = new LinkedHashMap<>();
    final Map<String, Definition.Permission> permissions = new LinkedHashMap<>();
    final Map<String, Definition.Role> roles = new LinkedHashMap<>();
    final Map<String, Definition.Connection> connections = new LinkedHashMap<>();
    final Map<String, Definition.Subscription> subscriptions = new LinkedHashMap<>();
    final Map<String, Definition.DispatchPool> dispatchPools = new LinkedHashMap<>();
    final Map<String, Definition.ScheduledJob> scheduledJobs = new LinkedHashMap<>();
    final Map<String, Definition.Process> processes = new LinkedHashMap<>();
    final Map<String, Definition.Doc> docs = new LinkedHashMap<>();
    final Map<String, String> firstModule = new LinkedHashMap<>();

    Validation(String app, boolean targetBase) {
      this.app = app;
      this.targetBase = targetBase;
    }

    void check(String module, Definition d) {
      switch (d) {
        case Definition.EventType e -> {
          fourPart(module, "event type", e.code(), true);
          blank(module, e.code(), "name", e.name());
          unique(module, "event type", e.code(), eventTypes, e);
        }
        case Definition.Permission p -> {
          fourPart(module, "permission", p.code(), true);
          unique(module, "permission", p.code(), permissions, p);
        }
        case Definition.Role r -> {
          if (r.name().contains(":")) {
            problem(module, r.name(), "role name must not include the application prefix; the platform adds '" + app + ":'");
          } else if (!CODE.matcher(r.name()).matches()) {
            problem(module, r.name(), "role name must match " + CODE.pattern());
          }
          for (String permission : r.permissions()) {
            fourPart(module, "permission of role '" + r.name() + "'", permission, false);
          }
          unique(module, "role", r.name(), roles, r);
        }
        case Definition.Connection c -> {
          code(module, "connection", c.code());
          blank(module, c.code(), "name", c.name());
          unique(module, "connection", c.code(), connections, c);
        }
        case Definition.Subscription s -> subscription(module, s);
        case Definition.DispatchPool p -> {
          code(module, "dispatch pool", p.code());
          blank(module, p.code(), "name", p.name());
          positive(module, p.code(), "rateLimit", p.rateLimit());
          positive(module, p.code(), "concurrency", p.concurrency());
          unique(module, "dispatch pool", p.code(), dispatchPools, p);
        }
        case Definition.ScheduledJob j -> scheduledJob(module, j);
        case Definition.Process p -> {
          String[] parts = p.code().split(":", -1);
          if (parts.length != 3 || !allSegments(parts)) {
            problem(module, p.code(), "process code must be {application}:{subdomain}:{process}");
          } else if (!parts[0].equals(app)) {
            problem(module, p.code(), "process code must start with the application '" + app + "'");
          }
          blank(module, p.code(), "name", p.name());
          unique(module, "process", p.code(), processes, p);
        }
        case Definition.Doc doc -> {
          if (!SLUG.matcher(doc.slug()).matches()) {
            problem(module, doc.slug(), "documentation slug must be kebab-case, matching " + SLUG.pattern());
          }
          blank(module, doc.slug(), "content", doc.content());
          unique(module, "documentation page", doc.slug(), docs, doc);
        }
      }
    }

    private void subscription(String module, Definition.Subscription s) {
      code(module, "subscription", s.code());
      blank(module, s.code(), "name", s.name());
      if (s.eventTypes().isEmpty()) {
        problem(module, s.code(), "a subscription needs at least one event type");
      }
      for (String eventType : s.eventTypes()) {
        fourPart(module, "event type of subscription '" + s.code() + "'", eventType, false);
      }
      if (s.sharedConnection() && s.connectionCode() == null) {
        problem(module, s.code(), "sharedConnection needs a connection code");
      }
      if (s.connectionCode() != null) {
        code(module, "connection of subscription '" + s.code() + "'", s.connectionCode());
      }
      if (s.dispatchPoolCode() != null) {
        code(module, "dispatch pool of subscription '" + s.code() + "'", s.dispatchPoolCode());
      }
      positive(module, s.code(), "maxRetries", s.maxRetries());
      positive(module, s.code(), "timeoutSeconds", s.timeoutSeconds());
      String target = s.target().trim();
      if (target.isEmpty()) {
        problem(module, s.code(), "target is blank");
      } else if (target.startsWith("/")) {
        if (!targetBase) {
          problem(
              module,
              s.code(),
              "target '" + target + "' is a path; set flowcatalyst.subscription-target-base-url or use an absolute URL");
        }
      } else if (!absoluteHttp(target)) {
        problem(module, s.code(), "target must be an absolute http(s) URL or a path starting with '/'");
      }
      unique(module, "subscription", s.code(), subscriptions, s);
    }

    private void scheduledJob(String module, Definition.ScheduledJob j) {
      code(module, "scheduled job", j.code());
      blank(module, j.code(), "name", j.name());
      if (j.crons().isEmpty()) {
        problem(module, j.code(), "a scheduled job needs at least one cron expression");
      }
      for (String cron : j.crons()) {
        int fields = cron.trim().isEmpty() ? 0 : cron.trim().split("\\s+").length;
        if (fields != 6) {
          problem(
              module,
              j.code(),
              "cron '" + cron + "' must have 6 fields, seconds first (a 5-field cron never fires)");
        }
      }
      positive(module, j.code(), "timeoutSeconds", j.timeoutSeconds());
      positive(module, j.code(), "deliveryMaxAttempts", j.deliveryMaxAttempts());
      String targetUrl = j.targetUrl();
      String target = targetUrl == null ? "" : targetUrl.trim();
      if (target.startsWith("/")) {
        if (!targetBase) {
          problem(
              module,
              j.code(),
              "targetUrl '" + target + "' is a path; set flowcatalyst.subscription-target-base-url or use an absolute URL");
        }
      } else if (!target.isEmpty() && !absoluteHttp(target)) {
        problem(module, j.code(), "targetUrl must be an absolute http(s) URL or a path starting with '/'");
      }
      unique(module, "scheduled job", j.code(), scheduledJobs, j);
    }

    /** Four non-empty lowercase segments; the first must be this application when {@code own}. */
    private void fourPart(String module, String what, String value, boolean own) {
      String[] parts = value.split(":", -1);
      if (parts.length != 4 || !allSegments(parts)) {
        problem(
            module,
            value,
            what + " code must be {application}:{domain}:{aggregate}:{action}, lowercase, matching "
                + SEGMENT.pattern() + " in each part");
      } else if (own && !parts[0].equals(app)) {
        problem(module, value, what + " code must start with the application '" + app + "'");
      }
    }

    private static boolean allSegments(String[] parts) {
      for (String p : parts) {
        if (!SEGMENT.matcher(p).matches()) {
          return false;
        }
      }
      return true;
    }

    private void code(String module, String what, String value) {
      if (!CODE.matcher(value).matches()) {
        problem(module, value, what + " code must match " + CODE.pattern());
      }
    }

    private void blank(String module, String key, String field, String value) {
      if (value.isBlank()) {
        problem(module, key, field + " is blank");
      }
    }

    private void positive(String module, String key, String field, @Nullable Integer value) {
      if (value != null && value <= 0) {
        problem(module, key, field + " must be positive");
      }
    }

    private <T> void unique(String module, String what, String key, Map<String, T> into, T item) {
      String id = what + "/" + key;
      String first = firstModule.putIfAbsent(id, module);
      if (first != null) {
        problem(module, key, "duplicate " + what + ", already declared by module '" + first + "'");
      } else {
        into.put(key, item);
      }
    }

    private void problem(String module, String key, String message) {
      problems.add(Problem.of(module, key, message));
    }

    private static boolean absoluteHttp(String value) {
      try {
        URI u = URI.create(value);
        String scheme = u.getScheme();
        return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && u.getHost() != null;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
  }
}
