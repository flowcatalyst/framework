package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.context.ClientId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Where modules register handlers for what the platform delivers to this application, in
 * {@code boot} (flowcatalyst contract, section 4). Provided by {@link FlowCatalystModule}, which
 * mounts the receiver routes under {@code flowcatalyst.receivers.path} (default {@code /fc}).
 *
 * <pre>{@code
 * Receivers receivers = app.get(Receivers.class);
 * receivers.onDispatch("orders:billing:invoice:created", (ctx, delivery) -> DispatchResult.ack());
 * receivers.onDispatch("orders:billing:*", (ctx, delivery) -> DispatchResult.defer());
 * receivers.onScheduledJob("nightly-export", (ctx, firing) -> JobResult.of(Map.of("rows", 42)));
 * }</pre>
 *
 * <p>Registration closes when the module's {@code started} hook runs; registering later throws
 * {@link IllegalStateException}. A duplicate or malformed registration throws at once, which
 * fails start-up.
 */
public final class Receivers {

  /**
   * The client in a handler's {@code Context} when the delivery names none: a platform-scoped
   * dispatch job, or a scheduled job. Platform client identifiers match
   * {@code [a-z0-9][a-z0-9-]*}, so this value can never be a real client.
   */
  public static final ClientId PLATFORM_CLIENT = new ClientId("_platform");

  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

  /** A job code; at most 186 characters so that {@code scheduled-job:{code}} is a valid lock name. */
  static final Pattern JOB_CODE = Pattern.compile("[A-Za-z0-9._:-]{1,186}");

  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, DispatchHandler> dispatchExact = new HashMap<>();
  private final Map<String, DispatchHandler> dispatchPrefix = new HashMap<>();
  private final Map<String, ScheduledJobHandler> jobs = new HashMap<>();
  private boolean sealed;
  private volatile boolean mounted;
  private volatile boolean ready;

  /** Creates an empty registry. Normally provided by the module; public for tests. */
  public Receivers() {}

  /**
   * Registers a dispatch-job handler.
   *
   * <p>The pattern is an exact type ({@code orders:billing:invoice:created}), a family ending in
   * {@code :*} on a segment boundary ({@code orders:billing:*} matches every type that starts with
   * {@code orders:billing:}), or {@code *} for everything. The most specific wins: an exact type,
   * then the longest family. Segments are {@code [A-Za-z0-9._-]+}, separated by {@code :}.
   *
   * @param pattern the event type or family
   * @param handler the handler
   * @throws IllegalArgumentException when the pattern is malformed or already registered
   * @throws IllegalStateException after start-up
   */
  public void onDispatch(String pattern, DispatchHandler handler) {
    boolean family = pattern.equals("*") || pattern.endsWith(":*");
    String key = family ? pattern.substring(0, Math.max(0, pattern.length() - 2)) : pattern;
    if (!pattern.equals("*")) {
      for (String segment : key.split(":", -1)) {
        if (!SEGMENT.matcher(segment).matches()) {
          throw new IllegalArgumentException(
              "dispatch handler pattern '"
                  + LogSafe.clean(pattern, 200)
                  + "' is malformed: use a type, a family ending in ':*', or '*'");
        }
      }
    }
    lock.lock();
    try {
      requireOpen();
      Map<String, DispatchHandler> target = family ? dispatchPrefix : dispatchExact;
      if (target.putIfAbsent(key, handler) != null) {
        throw new IllegalArgumentException(
            "a dispatch handler for '" + pattern + "' is already registered");
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Registers the handler of a scheduled job. The platform POSTs the job's firings to
   * {@code {prefix}/scheduled-jobs}; the job's {@code targetUrl} must point there.
   *
   * <p>Unless a firing says {@code concurrent}, the handler runs only while this application holds
   * the lock {@code scheduled-job:{jobCode}} (service scope) from the default lock instance, so
   * start-up fails when a job handler is registered and no lock instance is configured.
   *
   * @param jobCode the job's code, {@code [A-Za-z0-9._:-]{1,186}}
   * @param handler the handler
   * @throws IllegalArgumentException when the code is malformed or already registered
   * @throws IllegalStateException after start-up
   */
  public void onScheduledJob(String jobCode, ScheduledJobHandler handler) {
    if (!JOB_CODE.matcher(jobCode).matches()) {
      throw new IllegalArgumentException(
          "scheduled job code '"
              + LogSafe.clean(jobCode, 200)
              + "' is malformed: use [A-Za-z0-9._:-], at most 186 characters");
    }
    lock.lock();
    try {
      requireOpen();
      if (jobs.putIfAbsent(jobCode, handler) != null) {
        throw new IllegalArgumentException(
            "a handler for scheduled job '" + jobCode + "' is already registered");
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * The handler of a job.
   *
   * @param jobCode the job's code
   * @return the handler, if any
   */
  Optional<ScheduledJobHandler> jobHandler(String jobCode) {
    lock.lock();
    try {
      return Optional.ofNullable(jobs.get(jobCode));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Whether any scheduled-job handler is registered.
   *
   * @return true when at least one is
   */
  boolean hasJobHandlers() {
    lock.lock();
    try {
      return !jobs.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  /**
   * The registered job codes, for the start-up log.
   *
   * @return codes, unordered
   */
  List<String> jobCodes() {
    lock.lock();
    try {
      return new ArrayList<>(jobs.keySet());
    } finally {
      lock.unlock();
    }
  }

  /**
   * The handler for a type: exact, then the longest family, then {@code *}.
   *
   * @param type the job's event type
   * @return the handler, if any
   */
  Optional<DispatchHandler> dispatchHandler(String type) {
    lock.lock();
    try {
      DispatchHandler exact = dispatchExact.get(type);
      if (exact != null) {
        return Optional.of(exact);
      }
      String prefix = type;
      int colon;
      while ((colon = prefix.lastIndexOf(':')) > 0) {
        prefix = prefix.substring(0, colon);
        DispatchHandler h = dispatchPrefix.get(prefix);
        if (h != null) {
          return Optional.of(h);
        }
      }
      return Optional.ofNullable(dispatchPrefix.get(""));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Whether any dispatch handler is registered.
   *
   * @return true when at least one is
   */
  boolean hasDispatchHandlers() {
    lock.lock();
    try {
      return !dispatchExact.isEmpty() || !dispatchPrefix.isEmpty();
    } finally {
      lock.unlock();
    }
  }

  /**
   * The registered dispatch patterns, for the start-up log.
   *
   * @return patterns, unordered
   */
  List<String> dispatchPatterns() {
    lock.lock();
    try {
      List<String> out = new ArrayList<>(dispatchExact.keySet());
      for (String prefix : dispatchPrefix.keySet()) {
        out.add(prefix.isEmpty() ? "*" : prefix + ":*");
      }
      return out;
    } finally {
      lock.unlock();
    }
  }

  /** Records that the receiver routes are mounted. */
  void markMounted() {
    mounted = true;
  }

  /**
   * Whether the receiver routes are mounted.
   *
   * @return true once the module mounted them
   */
  boolean isMounted() {
    return mounted;
  }

  /** Opens the receivers to deliveries, once start-up checks passed. */
  void markReady() {
    ready = true;
  }

  /**
   * Whether deliveries are accepted. Until the module's {@code started} hook has checked the
   * registrations (the server may already be listening), deliveries are answered 503.
   *
   * @return true once ready
   */
  boolean isReady() {
    return ready;
  }

  /** Closes registration. */
  void seal() {
    lock.lock();
    try {
      sealed = true;
    } finally {
      lock.unlock();
    }
  }

  private void requireOpen() {
    if (sealed) {
      throw new IllegalStateException("receivers cannot be registered after start-up; register in boot");
    }
  }
}
