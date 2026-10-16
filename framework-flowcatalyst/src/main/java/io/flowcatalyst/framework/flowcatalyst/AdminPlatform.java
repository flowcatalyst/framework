package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.http.HttpException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the platform-backed admin sections share: how a platform call's failure becomes an answer
 * (admin contract sections 11 and 12), and the small checks on what a caller sends.
 */
final class AdminPlatform {
  private AdminPlatform() {}

  private static final Logger LOG = LoggerFactory.getLogger(AdminPlatform.class);

  /** A platform id as it may appear in a path: nothing that could change the path. */
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  /** The largest page the admin API serves. */
  static final int MAX_LIMIT = 200;

  /**
   * Runs a platform call. A failure is {@code 502 PLATFORM_ERROR} (the cause goes to the log, not to
   * the browser), except a {@code 404} when {@code notFoundCode} is given, a refusal the platform
   * explained for the person who asked ({@link PlatformException.Kind#REJECTED}: its status, code and
   * message, in its own words), and {@code 503 PLATFORM_NOT_CONFIGURED} when there is no connection.
   */
  static <T> T call(PlatformClient client, Supplier<T> call, @Nullable String notFoundCode, @Nullable String notFoundMessage) {
    return call(client, call, notFoundCode, notFoundMessage, false);
  }

  /** As {@link #call}, for a call that changes something (the 502 for a missing permission says so). */
  static <T> T write(PlatformClient client, Supplier<T> call, @Nullable String notFoundCode, @Nullable String notFoundMessage) {
    return call(client, call, notFoundCode, notFoundMessage, true);
  }

  private static <T> T call(
      PlatformClient client, Supplier<T> call, @Nullable String notFoundCode, @Nullable String notFoundMessage, boolean write) {
    if (!client.isEnabled()) {
      throw new HttpException(503, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection (flowcatalyst.enabled is false)");
    }
    try {
      return call.get();
    } catch (PlatformException e) {
      if (e.kind() == PlatformException.Kind.NOT_FOUND && notFoundCode != null) {
        throw new HttpException(404, notFoundCode, notFoundMessage == null ? "not found" : notFoundMessage);
      }
      if (e.kind() == PlatformException.Kind.REJECTED) {
        throw rejected(e);
      }
      // The cause can name hosts and request details: it goes to the log, not to the browser. It never
      // holds a request or response body (PlatformHttp reads the platform's code and message only).
      LOG.warn("an admin call to the platform failed ({}): {}", e.kind(), e.getMessage());
      if (e.kind() == PlatformException.Kind.FORBIDDEN) {
        throw new HttpException(
            502,
            "PLATFORM_ERROR",
            write
                ? "the platform refused this change for the application's service account; it needs the platform's permission to manage this resource"
                : "the platform refused this read for the application's service account; it needs the platform's view permission for this resource");
      }
      throw new HttpException(502, "PLATFORM_ERROR", "the platform could not be reached or answered with an error");
    }
  }

  /** The platform said no and said why: its code and message, with the status it used (a 500 is a 502 here). */
  private static HttpException rejected(PlatformException e) {
    int status = e.status() >= 500 ? 502 : e.status();
    String code = e.code();
    if (code == null || code.isBlank()) {
      code = "REQUEST_REFUSED";
    } else if (code.equals("FORBIDDEN")) {
      // A 403 with a reason of its own (the client is not available to this application): not the
      // admin gate's FORBIDDEN, which means the signed-in user lacks a permission.
      code = "CLIENT_NOT_AVAILABLE";
    }
    String message = e.serverMessage();
    return new HttpException(status, code, message == null || message.isBlank() ? "the platform refused the request" : message);
  }

  /** The id as it may appear in a path, or a {@code 404} with the section's own code (the platform is not asked). */
  static String id(String id, String notFoundCode, String notFoundMessage) {
    if (!ID.matcher(id).matches()) {
      throw new HttpException(404, notFoundCode, notFoundMessage);
    }
    return id;
  }

  /** A page size: 1 to {@link #MAX_LIMIT}. */
  static int limit(int limit) {
    return Math.max(1, Math.min(MAX_LIMIT, limit));
  }

  /** A cursor that is a non-negative number (a page or an offset), or {@code 0} for none. */
  static int cursor(@Nullable String after) {
    if (after == null || after.isEmpty()) {
      return 0;
    }
    try {
      int value = Integer.parseInt(after);
      if (value >= 0) {
        return value;
      }
    } catch (NumberFormatException ignored) {
      // fall through
    }
    throw new HttpException(400, "INVALID_CURSOR", "the cursor is not one this server gave");
  }

  static @Nullable String blankToNull(@Nullable String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
