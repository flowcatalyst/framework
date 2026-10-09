package io.flowcatalyst.framework.flowcatalyst;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Something an application declares to the platform: an event type, a permission, a role, a
 * subscription, a connection, a dispatch pool, a scheduled job, a process or a documentation page.
 *
 * <p>Modules contribute these during {@code register} through {@link Definitions}. The types here
 * are the framework's own; they are mapped to the SDK's types at sync time.
 */
public sealed interface Definition {

  /**
   * An event type the application publishes.
   *
   * @param code {@code {application}:{domain}:{aggregate}:{action}}
   * @param name display name
   * @param description what it means, if worth saying
   */
  record EventType(String code, String name, @Nullable String description) implements Definition {}

  /**
   * A permission the application defines. Permissions reach the platform only through the roles
   * that grant them; a permission no role grants is reported as a warning when syncing.
   *
   * @param code {@code {application}:{context}:{aggregate}:{action}}
   * @param description what it allows
   */
  record Permission(String code, @Nullable String description) implements Definition {}

  /**
   * A role of the application.
   *
   * @param name the role name without the application prefix; the platform stores
   *     {@code {application}:{name}}
   * @param permissions permission codes the role grants, each {@code app:context:aggregate:action}
   * @param displayName display name, if different from the name
   * @param description what the role is for
   */
  record Role(
      String name,
      List<String> permissions,
      @Nullable String displayName,
      @Nullable String description)
      implements Definition {

    /** Copies the permission list. */
    public Role {
      permissions = List.copyOf(permissions);
    }

    /**
     * A role with a name and permissions.
     *
     * @param name the role name without the application prefix
     * @param permissions the permission codes it grants
     * @return the role
     */
    public static Role of(String name, String... permissions) {
      return new Role(name, List.of(permissions), null, null);
    }

    /**
     * Sets the display name.
     *
     * @param displayName the display name
     * @return a copy
     */
    public Role withDisplayName(String displayName) {
      return new Role(name, permissions, displayName, description);
    }

    /**
     * Sets the description.
     *
     * @param description the description
     * @return a copy
     */
    public Role withDescription(String description) {
      return new Role(name, permissions, displayName, description);
    }
  }

  /** How a failed delivery affects later deliveries of the same message group. */
  enum DeliveryMode {
    /** Deliver independently; a failure does not hold later deliveries. */
    IMMEDIATE,
    /** After a failure, hold later deliveries of the same message group. */
    BLOCK_ON_ERROR
  }

  /**
   * A subscription: events of the listed types are delivered to the target.
   *
   * @param code the subscription code
   * @param name display name
   * @param description what it is for
   * @param target an absolute http(s) URL, or a path when {@code
   *     flowcatalyst.subscription-target-base-url} is set
   * @param eventTypes codes of the event types that trigger it
   * @param connectionCode the connection delivering it, owned by this application unless {@code
   *     sharedConnection}
   * @param sharedConnection true when {@code connectionCode} names a shared connection
   * @param dispatchPoolCode the dispatch pool, when not the platform default
   * @param mode the delivery mode, when not the platform default
   * @param maxRetries maximum attempts, when not the platform default
   * @param timeoutSeconds delivery timeout, when not the platform default
   * @param dataOnly true to deliver only the event's data, without the envelope
   */
  record Subscription(
      String code,
      String name,
      @Nullable String description,
      String target,
      List<String> eventTypes,
      @Nullable String connectionCode,
      boolean sharedConnection,
      @Nullable String dispatchPoolCode,
      @Nullable DeliveryMode mode,
      @Nullable Integer maxRetries,
      @Nullable Integer timeoutSeconds,
      @Nullable Boolean dataOnly)
      implements Definition {

    /** Copies the event type list. */
    public Subscription {
      eventTypes = List.copyOf(eventTypes);
    }

    /**
     * A subscription with the required parts.
     *
     * @param code the subscription code
     * @param name display name
     * @param target where to deliver
     * @param eventTypes the event type codes that trigger it
     * @return the subscription
     */
    public static Subscription of(
        String code, String name, String target, String... eventTypes) {
      return new Subscription(
          code, name, null, target, List.of(eventTypes), null, false, null, null, null, null, null);
    }

    /**
     * Delivers through a connection owned by this application.
     *
     * @param connectionCode the connection code
     * @return a copy
     */
    public Subscription viaConnection(String connectionCode) {
      return new Subscription(
          code, name, description, target, eventTypes, connectionCode, false, dispatchPoolCode,
          mode, maxRetries, timeoutSeconds, dataOnly);
    }

    /**
     * Delivers through a shared connection.
     *
     * @param connectionCode the shared connection code
     * @return a copy
     */
    public Subscription viaSharedConnection(String connectionCode) {
      return new Subscription(
          code, name, description, target, eventTypes, connectionCode, true, dispatchPoolCode,
          mode, maxRetries, timeoutSeconds, dataOnly);
    }

    /**
     * Uses a dispatch pool.
     *
     * @param dispatchPoolCode the pool code
     * @return a copy
     */
    public Subscription inPool(String dispatchPoolCode) {
      return new Subscription(
          code, name, description, target, eventTypes, connectionCode, sharedConnection,
          dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
    }

    /**
     * Sets the delivery mode.
     *
     * @param mode the mode
     * @return a copy
     */
    public Subscription withMode(DeliveryMode mode) {
      return new Subscription(
          code, name, description, target, eventTypes, connectionCode, sharedConnection,
          dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
    }

    /**
     * Sets the description.
     *
     * @param description the description
     * @return a copy
     */
    public Subscription withDescription(String description) {
      return new Subscription(
          code, name, description, target, eventTypes, connectionCode, sharedConnection,
          dispatchPoolCode, mode, maxRetries, timeoutSeconds, dataOnly);
    }
  }

  /**
   * A connection: how the platform authenticates deliveries for subscriptions that name it. The
   * platform assigns the service account; nothing secret is declared here.
   *
   * @param code the connection code
   * @param name display name
   * @param description what it is for
   */
  record Connection(String code, String name, @Nullable String description) implements Definition {}

  /**
   * A dispatch pool: a rate and concurrency limit for outbound delivery.
   *
   * @param code the pool code
   * @param name display name
   * @param description what it is for
   * @param rateLimit requests per minute, when not the platform default
   * @param concurrency simultaneous deliveries, when not the platform default
   */
  record DispatchPool(
      String code,
      String name,
      @Nullable String description,
      @Nullable Integer rateLimit,
      @Nullable Integer concurrency)
      implements Definition {}

  /**
   * A scheduled job the platform fires at the application.
   *
   * @param code the job code
   * @param name display name
   * @param description what it does
   * @param crons six-field, seconds-first cron expressions
   * @param timezone an IANA zone, when not UTC
   * @param payload JSON-serialisable payload delivered with each firing
   * @param concurrent whether a firing may start while the previous one runs
   * @param tracksCompletion whether the application reports completion of each firing
   * @param timeoutSeconds how long a firing may run
   * @param deliveryMaxAttempts attempts to deliver one firing
   * @param targetUrl overrides the application's default callback URL
   */
  record ScheduledJob(
      String code,
      String name,
      @Nullable String description,
      List<String> crons,
      @Nullable String timezone,
      @Nullable Object payload,
      @Nullable Boolean concurrent,
      @Nullable Boolean tracksCompletion,
      @Nullable Integer timeoutSeconds,
      @Nullable Integer deliveryMaxAttempts,
      @Nullable String targetUrl)
      implements Definition {

    /** Copies the cron list. */
    public ScheduledJob {
      crons = List.copyOf(crons);
    }

    /**
     * A job with the required parts.
     *
     * @param code the job code
     * @param name display name
     * @param crons six-field, seconds-first cron expressions
     * @return the job
     */
    public static ScheduledJob of(String code, String name, String... crons) {
      return new ScheduledJob(
          code, name, null, List.of(crons), null, null, null, null, null, null, null);
    }

    /**
     * Sets whether the application reports completion of each firing.
     *
     * @param tracksCompletion true to track completion
     * @return a copy
     */
    public ScheduledJob tracking(boolean tracksCompletion) {
      return new ScheduledJob(
          code, name, description, crons, timezone, payload, concurrent, tracksCompletion,
          timeoutSeconds, deliveryMaxAttempts, targetUrl);
    }

    /**
     * Sets the time zone.
     *
     * @param timezone an IANA zone
     * @return a copy
     */
    public ScheduledJob inZone(String timezone) {
      return new ScheduledJob(
          code, name, description, crons, timezone, payload, concurrent, tracksCompletion,
          timeoutSeconds, deliveryMaxAttempts, targetUrl);
    }
  }

  /**
   * A process: documentation of a workflow, usually a diagram.
   *
   * @param code {@code {application}:{subdomain}:{process}}
   * @param name display name
   * @param description what the process is
   * @param body the diagram source, usually Mermaid
   * @param diagramType the diagram language, when not Mermaid
   * @param tags free-form tags
   */
  record Process(
      String code,
      String name,
      @Nullable String description,
      @Nullable String body,
      @Nullable String diagramType,
      @Nullable List<String> tags)
      implements Definition {}

  /**
   * A documentation page shown under the application on the platform.
   *
   * @param slug URL-safe kebab-case page id
   * @param content Markdown
   * @param title the title, derived from the first heading when absent
   */
  record Doc(String slug, String content, @Nullable String title) implements Definition {}
}
