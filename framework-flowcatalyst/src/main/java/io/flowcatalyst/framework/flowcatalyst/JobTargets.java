package io.flowcatalyst.framework.flowcatalyst;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * Where the platform delivers a scheduled job's firings (flowcatalyst contract section 4c). The
 * platform has no default target: a job synced without one fails every firing with {@code No target
 * URL configured for job}. A job that names none is therefore delivered to this service's own
 * scheduled-job receiver, and a path target is resolved against the configured base like a
 * subscription's.
 */
final class JobTargets {

  private final @Nullable String base;
  private final boolean receiversEnabled;
  private final boolean hasJobHandlers;
  private final String receiversPath;

  /**
   * Captures the settings and the registry the target depends on.
   *
   * @param base {@code flowcatalyst.subscription-target-base-url}, when set
   * @param receiversEnabled {@code flowcatalyst.receivers.enabled}
   * @param hasJobHandlers whether any scheduled-job handler is registered
   * @param receiversPath {@code flowcatalyst.receivers.path}
   */
  JobTargets(
      @Nullable URI base, boolean receiversEnabled, boolean hasJobHandlers, String receiversPath) {
    String b = base == null ? "" : base.toString().trim();
    this.base = b.isEmpty() ? null : b;
    this.receiversEnabled = receiversEnabled;
    this.hasJobHandlers = hasJobHandlers;
    this.receiversPath = receiversPath;
  }

  /**
   * The target to send for a declared one.
   *
   * @param declared the job's {@code targetUrl}, absolute or a path, or null
   * @return the URL to send, or null to send none
   */
  @Nullable String resolve(@Nullable String declared) {
    String target = declared == null ? "" : declared.trim();
    if (base == null) {
      return target.isEmpty() ? null : target;
    }
    if (target.isEmpty()) {
      if (!hasJobHandlers || !receiversEnabled) {
        return null;
      }
      target = receiversPath + "/scheduled-jobs";
    }
    if (target.startsWith("/")) {
      return base.replaceAll("/+$", "") + target;
    }
    return target;
  }
}
