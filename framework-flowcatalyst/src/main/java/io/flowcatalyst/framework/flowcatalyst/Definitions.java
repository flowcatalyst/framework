package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.core.AppBuilder;

/**
 * Where a module declares what its application contributes to the platform, during
 * {@code register}:
 *
 * <pre>{@code
 * public void register(AppBuilder app) {
 *   Definitions.in(app)
 *       .eventType("orders:sales:order:placed", "Order placed", "A customer placed an order.")
 *       .permission("orders:sales:order:read", "Read orders")
 *       .role("order-clerk", "orders:sales:order:read")
 *       .dispatchPool(new Definition.DispatchPool("orders-pool", "Orders", null, 300, 10));
 * }
 * }</pre>
 *
 * <p>Nothing is checked here: settings are not readable during {@code register}, and a code must be
 * compared with {@code flowcatalyst.application}. All definitions are validated together when the
 * application starts (see {@link DefinitionCatalog}), and every problem names the module that
 * contributed it.
 */
public final class Definitions {

  /** One definition and the module that contributed it. */
  record Contribution(String module, Definition definition) {}

  private final AppBuilder app;
  private final String module;

  private Definitions(AppBuilder app) {
    this.app = app;
    this.module = app.moduleId();
  }

  /**
   * Starts contributing on behalf of the module being registered.
   *
   * @param app the builder passed to {@code register}
   * @return the contributor, valid inside this {@code register} call
   * @throws IllegalStateException outside a module's {@code register} hook
   */
  public static Definitions in(AppBuilder app) {
    return new Definitions(app);
  }

  /**
   * Declares an event type.
   *
   * @param code {@code {application}:{domain}:{aggregate}:{action}}
   * @param name display name
   * @param description what it means
   * @return this
   */
  public Definitions eventType(String code, String name, String description) {
    return add(new Definition.EventType(code, name, description));
  }

  /**
   * Declares a permission.
   *
   * @param code {@code {application}:{context}:{aggregate}:{action}}
   * @param description what it allows
   * @return this
   */
  public Definitions permission(String code, String description) {
    return add(new Definition.Permission(code, description));
  }

  /**
   * Declares a role.
   *
   * @param name the role name without the application prefix
   * @param permissions the permission codes it grants
   * @return this
   */
  public Definitions role(String name, String... permissions) {
    return add(Definition.Role.of(name, permissions));
  }

  /**
   * Declares a role with display name or description.
   *
   * @param role the role
   * @return this
   */
  public Definitions role(Definition.Role role) {
    return add(role);
  }

  /**
   * Declares a subscription.
   *
   * @param subscription the subscription
   * @return this
   */
  public Definitions subscription(Definition.Subscription subscription) {
    return add(subscription);
  }

  /**
   * Declares a connection.
   *
   * @param code the connection code
   * @param name display name
   * @return this
   */
  public Definitions connection(String code, String name) {
    return add(new Definition.Connection(code, name, null));
  }

  /**
   * Declares a dispatch pool.
   *
   * @param pool the pool
   * @return this
   */
  public Definitions dispatchPool(Definition.DispatchPool pool) {
    return add(pool);
  }

  /**
   * Declares a scheduled job.
   *
   * @param job the job
   * @return this
   */
  public Definitions scheduledJob(Definition.ScheduledJob job) {
    return add(job);
  }

  /**
   * Declares a process.
   *
   * @param process the process
   * @return this
   */
  public Definitions process(Definition.Process process) {
    return add(process);
  }

  /**
   * Declares a documentation page. The Java SDK cannot sync documentation yet, so the page is
   * accepted and skipped, with a warning, when syncing.
   *
   * @param slug URL-safe kebab-case page id
   * @param content Markdown
   * @return this
   */
  public Definitions docs(String slug, String content) {
    return add(new Definition.Doc(slug, content, null));
  }

  private Definitions add(Definition definition) {
    app.contribute(Contribution.class, new Contribution(module, definition));
    return this;
  }
}
