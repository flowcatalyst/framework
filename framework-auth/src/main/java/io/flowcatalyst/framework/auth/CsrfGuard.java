package io.flowcatalyst.framework.auth;

import io.flowcatalyst.framework.http.HttpException;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.pipeline.Middleware;
import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The platform's F16 rules for unsafe requests (contract section 7.2). {@link #forSessions} is
 * applied by the framework to every unsafe request authenticated by the session cookie: {@code
 * Sec-Fetch-Site} must be {@code same-origin} or {@code none}, else {@code Origin} must be the
 * application's origin, and a request with neither is refused ({@code 403 CROSS_ORIGIN_REQUEST});
 * then a body must be JSON ({@code 415}). {@link #publicForm} is the variant for an application's
 * own public {@code POST} routes (a sign-up form): a request with neither header passes, and the
 * body must be JSON for everyone.
 */
public final class CsrfGuard {

  private static final Logger LOG = LoggerFactory.getLogger(CsrfGuard.class);

  private final String origin;
  private final boolean refuseWithoutProvenance;

  private CsrfGuard(URI publicBaseUrl, boolean refuseWithoutProvenance) {
    this.origin = origin(publicBaseUrl);
    this.refuseWithoutProvenance = refuseWithoutProvenance;
  }

  /**
   * The guard for cookie-authenticated requests.
   *
   * @param publicBaseUrl the application's public base URL; its origin is the trusted one
   * @return the guard
   */
  public static CsrfGuard forSessions(URI publicBaseUrl) {
    return new CsrfGuard(publicBaseUrl, true);
  }

  /**
   * The guard for an application's public unsafe routes (the platform's CredentialCSRF rule).
   *
   * @param publicBaseUrl the application's public base URL
   * @return the guard
   */
  public static CsrfGuard publicForm(URI publicBaseUrl) {
    return new CsrfGuard(publicBaseUrl, false);
  }

  /**
   * Checks a request; safe methods always pass.
   *
   * @param request the request
   * @throws HttpException {@code 403 CROSS_ORIGIN_REQUEST} or {@code 415 UNSUPPORTED_MEDIA_TYPE}
   */
  public void check(Request request) {
    if (!unsafe(request.method())) {
      return;
    }
    String site = request.header("Sec-Fetch-Site");
    String sentOrigin = request.header("Origin");
    boolean allowed;
    if (site != null) {
      String s = site.trim().toLowerCase(Locale.ROOT);
      allowed = s.equals("same-origin") || s.equals("none");
    } else if (sentOrigin != null) {
      allowed = origin.equals(normalisedOrigin(sentOrigin));
    } else {
      allowed = !refuseWithoutProvenance;
    }
    if (!allowed) {
      LOG.info(
          "refused a cross-origin {} {} (Sec-Fetch-Site: {}, Origin: {})",
          request.method(),
          request.path(),
          LogSafe.of(site),
          LogSafe.of(sentOrigin));
      throw new HttpException(403, "CROSS_ORIGIN_REQUEST", "cross-origin requests are not allowed here");
    }
    if (hasBody(request) && !json(request.header("Content-Type"))) {
      throw new HttpException(415, "UNSUPPORTED_MEDIA_TYPE", "content type must be application/json");
    }
  }

  /**
   * The guard as route middleware, for public routes.
   *
   * @return the middleware
   */
  public Middleware<Request, Response> middleware() {
    return (request, next) -> {
      check(request);
      return next.handle(request);
    };
  }

  static boolean unsafe(Method method) {
    return method != Method.GET && method != Method.HEAD && method != Method.OPTIONS;
  }

  private static boolean hasBody(Request request) {
    if (request.contentLength() > 0) {
      return true;
    }
    String length = request.header("Content-Length");
    if (length != null) {
      try {
        if (Long.parseLong(length.trim()) > 0) {
          return true;
        }
      } catch (NumberFormatException e) {
        return true;
      }
    }
    return request.header("Transfer-Encoding") != null;
  }

  private static boolean json(@Nullable String contentType) {
    if (contentType == null) {
      return false;
    }
    String media = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return media.equals("application/json") || (media.startsWith("application/") && media.endsWith("+json"));
  }

  static String origin(URI uri) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    boolean defaultPort = port < 0 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
    return scheme + "://" + host + (defaultPort ? "" : ":" + port);
  }

  private static @Nullable String normalisedOrigin(String value) {
    try {
      URI uri = URI.create(value.trim());
      if (uri.getScheme() == null || uri.getHost() == null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
        return null;
      }
      return origin(uri);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Header values in a log line: at most 100 characters, no control characters. */
  static final class LogSafe {
    private LogSafe() {}

    static String of(@Nullable String value) {
      if (value == null) {
        return "-";
      }
      String cut = value.length() > 100 ? value.substring(0, 100) : value;
      return cut.replaceAll("[\\p{Cntrl}]", "?");
    }
  }
}
