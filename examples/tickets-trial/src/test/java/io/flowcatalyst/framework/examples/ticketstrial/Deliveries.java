package io.flowcatalyst.framework.examples.ticketstrial;

/**
 * The signing secret this service's tests configure for the receivers. What the platform sends,
 * signed, comes from the framework's {@code ReceiverTesting} ({@code Delivery.ofEvent}, {@code
 * deliver}, {@code fire}, {@code firingBody}, {@code headers}); this class carried its own copies
 * until the framework published them (friction log F12, then fulfil-go fixes A7).
 */
final class Deliveries {

  /** The secret the test application is configured with. Not a real secret. */
  static final String SECRET = "whsec-tickets-test-only";

  private Deliveries() {}
}
