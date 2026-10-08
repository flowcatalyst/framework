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
   * Stops background work started in {@link #boot}. Called in reverse module order on shutdown.
   *
   * @param app the application being shut down
   * @throws Exception any failure; it is logged and does not stop the shutdown
   */
  default void shutdown(App app) throws Exception {}
}
