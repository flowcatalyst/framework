package io.flowcatalyst.framework.core;

/**
 * A unit of the application: it declares what it needs and provides during {@link #register},
 * then does its work in {@link #boot}.
 *
 * <p>Ids are lowercase, match {@code [a-z][a-z0-9-]*} and are unique in an application.
 */
public interface Module {

  /**
   * The module id.
   *
   * @return a unique id matching {@code [a-z][a-z0-9-]*}
   */
  String id();

  /**
   * Declares settings, provides services, registers drivers and contributes migrations. Must not
   * build services, read configuration values or start threads.
   *
   * @param app the builder, valid for the duration of this call
   * @throws Exception any failure; it is reported as a start-up problem naming this module
   */
  default void register(AppBuilder app) throws Exception {}

  /**
   * Reads configuration, looks up services, adds health checks and starts background work. Must
   * not provide new services.
   *
   * @param app the running application
   * @throws Exception any failure; it is reported as a start-up problem naming this module
   */
  default void boot(App app) throws Exception {}

  /**
   * Called after <em>every</em> module's {@link #boot} has completed, in module order. Use it for
   * work that needs the whole application assembled, such as compiling a route table that other
   * modules register into during {@code boot}, or starting to accept traffic. Must not provide new
   * services.
   *
   * <p>If any {@code boot} failed, no {@code started} hook runs. Modules whose {@code boot} ran are
   * shut down either way.
   *
   * @param app the running application
   * @throws Exception any failure; it is reported as a start-up problem naming this module
   */
  default void started(App app) throws Exception {}

  /**
   * Stops background work started in {@link #boot} or {@link #started}. Called in reverse module order on shutdown.
   *
   * @param app the application being shut down
   * @throws Exception any failure; it is logged and does not stop the shutdown
   */
  default void shutdown(App app) throws Exception {}
}
