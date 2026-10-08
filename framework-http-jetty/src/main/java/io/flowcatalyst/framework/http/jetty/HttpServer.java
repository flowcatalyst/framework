package io.flowcatalyst.framework.http.jetty;

import io.flowcatalyst.framework.http.Dispatcher;
import io.flowcatalyst.framework.http.HttpErrors;
import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.RawRequest;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import io.flowcatalyst.framework.core.MonotonicClock;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The running Jetty server. Provided by {@link JettyHttpModule}; started in its {@code boot} and
 * stopped in its {@code shutdown}.
 */
public final class HttpServer {

  private static final Logger LOG = LoggerFactory.getLogger(HttpServer.class);

  private final String host;
  private final int requestedPort;
  private final long maxBodyBytes;
  private final Duration requestTimeout;
  private final @Nullable Duration configuredGrace;
  private Duration shutdownGrace = Duration.ZERO;
  private final MonotonicClock clock;

  private final AtomicBoolean draining = new AtomicBoolean();
  private final Set<Thread> inFlight = ConcurrentHashMap.newKeySet();

  private @Nullable Server server;
  private @Nullable ServerConnector connector;

  HttpServer(
      String host,
      int port,
      long maxBodyBytes,
      Duration requestTimeout,
      @Nullable Duration shutdownGrace,
      MonotonicClock clock) {
    this.host = host;
    this.requestedPort = port;
    this.maxBodyBytes = maxBodyBytes;
    this.requestTimeout = requestTimeout;
    this.configuredGrace = shutdownGrace;
    this.clock = clock;
  }

  /**
   * The port the server is bound to; useful when {@code http.port} is {@code 0}.
   *
   * @return the bound port
   * @throws IllegalStateException when the server is not running
   */
  public int port() {
    ServerConnector c = connector;
    if (c == null || !c.isOpen()) {
      throw new IllegalStateException("the HTTP server is not listening");
    }
    return c.getLocalPort();
  }

  /**
   * Whether shutdown has begun. New requests get 503 and {@code /health/ready} reports down.
   *
   * @return true once shutdown has started
   */
  public boolean isDraining() {
    return draining.get();
  }

  /**
   * Starts listening.
   *
   * @param dispatcher what handles requests
   * @param defaultGrace the shutdown grace when {@code http.shutdown-grace} is not set
   * @throws IllegalStateException when the address cannot be bound
   */
  void start(Dispatcher dispatcher, Duration defaultGrace) {
    Duration configured = configuredGrace;
    this.shutdownGrace = configured != null ? configured : defaultGrace;
    QueuedThreadPool pool = new QueuedThreadPool();
    pool.setName("http");
    pool.setVirtualThreadsExecutor(
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("http-request-", 0).factory()));
    Server s = new Server(pool);
    HttpConfiguration config = new HttpConfiguration();
    config.setSendServerVersion(false);
    config.setSendXPoweredBy(false);
    config.setIdleTimeout(requestTimeout.toMillis());
    ServerConnector c = new ServerConnector(s, new HttpConnectionFactory(config));
    c.setHost(host.equals("0.0.0.0") ? null : host);
    c.setPort(requestedPort);
    c.setIdleTimeout(requestTimeout.toMillis());
    s.addConnector(c);
    s.setStopTimeout(0);
    s.setErrorHandler(new JettyErrors());
    s.setHandler(new RequestHandler(dispatcher));
    try {
      s.start();
    } catch (Exception e) {
      try {
        s.stop();
      } catch (Exception suppressed) {
        e.addSuppressed(suppressed);
      }
      throw new IllegalStateException(
          "cannot listen on " + host + ":" + requestedPort + ": " + rootMessage(e), e);
    }
    this.server = s;
    this.connector = c;
    LOG.info("HTTP server listening on {}:{}", host, c.getLocalPort());
  }

  /** Stops accepting, lets in-flight requests finish up to the grace period, then stops. */
  void stop() {
    draining.set(true);
    Server s = server;
    ServerConnector c = connector;
    if (s == null || c == null) {
      return;
    }
    c.shutdown(); // closes the listening socket; existing connections stay
    long deadline = clock.nanos() + shutdownGrace.toNanos();
    while (!inFlight.isEmpty() && clock.nanos() < deadline) {
      LockSupport.parkNanos(5_000_000L);
    }
    if (!inFlight.isEmpty()) {
      LOG.warn("{} request(s) still running after the {} grace period; interrupting", inFlight.size(), shutdownGrace);
      for (Thread t : inFlight) {
        t.interrupt();
      }
    }
    try {
      s.stop();
    } catch (Exception e) {
      LOG.warn("error stopping the HTTP server", e);
    }
    server = null;
  }

  private static String rootMessage(Throwable t) {
    Throwable r = t;
    while (r.getCause() != null && r.getCause() != r) {
      r = r.getCause();
    }
    String m = r.getMessage();
    return m == null ? r.getClass().getSimpleName() : m;
  }

  // ---- request handling ------------------------------------------------------------------

  private final class RequestHandler extends Handler.Abstract {
    private final Dispatcher dispatcher;

    RequestHandler(Dispatcher dispatcher) {
      this.dispatcher = dispatcher;
    }

    @Override
    public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) {
      Thread self = Thread.currentThread();
      inFlight.add(self);
      try {
        Response out = respond(request);
        write(out, response);
        callback.succeeded();
      } catch (Throwable t) {
        callback.failed(t);
      } finally {
        inFlight.remove(self);
        Thread.interrupted(); // a drain interrupt must not leak into the pooled carrier
      }
      return true;
    }

    private Response respond(org.eclipse.jetty.server.Request request) {
      if (draining.get()) {
        return HttpErrors.error(503, "UNAVAILABLE", "the server is shutting down").header("Connection", "close");
      }
      Method method = Method.parse(request.getMethod());
      if (method == null) {
        return HttpErrors.error(405, "METHOD_NOT_ALLOWED", "method not allowed")
            .header("Allow", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT");
      }
      Map<String, List<String>> headers = new LinkedHashMap<>();
      for (HttpField f : request.getHeaders()) {
        headers.computeIfAbsent(f.getName(), k -> new ArrayList<>()).add(f.getValue());
      }
      InputStream body = Content.Source.asInputStream(request);
      RawRequest raw =
          new RawRequest(
              method,
              request.getHttpURI().getPath(),
              request.getHttpURI().getQuery(),
              headers,
              body,
              request.getLength(),
              maxBodyBytes,
              request.getConnectionMetaData().getProtocol());
      Response response = dispatcher.handle(Request.of(raw));
      if (response.status() == 413) {
        response = response.header("Connection", "close");
      }
      return response;
    }

    private void write(Response out, org.eclipse.jetty.server.Response response) throws IOException {
      response.setStatus(out.status());
      HttpFields.Mutable fields = response.getHeaders();
      for (Response.Header h : out.headers()) {
        fields.add(h.name(), h.value());
      }
      switch (out.body()) {
        case Response.Body.Empty e -> {
          if (out.status() != 204 && out.status() != 304) {
            fields.put(HttpHeader.CONTENT_LENGTH, 0L);
          }
        }
        case Response.Body.Bytes b -> fields.put(HttpHeader.CONTENT_LENGTH, (long) b.content().length);
        case Response.Body.Stream s -> {
          // chunked
        }
      }
      if (out.body() instanceof Response.Body.Empty && (out.status() == 204 || out.status() == 304)) {
        return;
      }
      try (OutputStream os = Content.Sink.asOutputStream(response)) {
        if (!out.writeBody(os)) {
          throw new IOException("client disconnected");
        }
      }
    }
  }

  /** Jetty's own errors (malformed requests, timeouts) in the platform envelope; no stack traces. */
  private static final class JettyErrors implements org.eclipse.jetty.server.Request.Handler {
    @Override
    public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback) {
      int status = response.getStatus();
      String code =
          switch (status) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 408 -> "REQUEST_TIMEOUT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 414, 431 -> "VALIDATION";
            case 503 -> "UNAVAILABLE";
            default -> status >= 500 ? "INTERNAL" : "VALIDATION";
          };
      String message = status >= 500 ? "internal error" : "bad request";
      response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json");
      String json = "{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}";
      byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
      response.write(true, ByteBuffer.wrap(bytes), callback);
      return true;
    }
  }
}
