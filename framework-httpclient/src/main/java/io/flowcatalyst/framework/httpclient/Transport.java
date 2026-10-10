package io.flowcatalyst.framework.httpclient;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientAgent;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * One exchange over Vert.x, blocking the calling (virtual) thread until it ends. The only class
 * that imports Vert.x. Redirects are never followed; the response body is bounded while it arrives.
 */
final class Transport implements AutoCloseable {

  /** What to send. {@code target} is absolute and already encoded. */
  record Outgoing(
      RequestMethod method,
      URI target,
      Map<String, List<String>> headers,
      byte @Nullable [] body,
      Duration connectTimeout,
      Duration timeout,
      long maxBytes) {}

  /** What came back; header names in lower case. */
  record Incoming(int status, Map<String, List<String>> headers, byte[] body) {}

  /** No response: the kind and the engine's cause (never shown to callers in a message). */
  static final class Failure extends Exception {
    private static final long serialVersionUID = 1L;
    final HttpClientException.Kind kind;

    Failure(HttpClientException.Kind kind, @Nullable Throwable cause) {
      super(kind.name(), cause, false, false);
      this.kind = kind;
    }
  }

  private final Vertx vertx;
  private final HttpClientAgent http;
  private volatile boolean closed;

  Transport(int eventLoops, int maxConnectionsPerHost) {
    this.vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(eventLoops));
    this.http =
        vertx.createHttpClient(
            new HttpClientOptions()
                .setKeepAlive(true)
                .setVerifyHost(true)
                .setTrustAll(false)
                .setDecompressionSupported(true)
                .setMaxRedirects(0),
            new PoolOptions().setHttp1MaxSize(maxConnectionsPerHost).setHttp2MaxSize(maxConnectionsPerHost));
  }

  Incoming exchange(Outgoing out) throws Failure, InterruptedException {
    if (closed) {
      throw new IllegalStateException("the HTTP client engine is closed");
    }
    URI target = out.target();
    boolean https = "https".equalsIgnoreCase(target.getScheme());
    int port = target.getPort() != -1 ? target.getPort() : https ? 443 : 80;
    String uri = target.getRawPath() + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
    MultiMap headers = MultiMap.caseInsensitiveMultiMap();
    out.headers().forEach(headers::add);
    RequestOptions options =
        new RequestOptions()
            .setMethod(HttpMethod.valueOf(out.method().name()))
            .setHost(target.getHost())
            .setPort(port)
            .setSsl(https)
            .setURI(uri)
            .setHeaders(headers)
            .setConnectTimeout(out.connectTimeout().toMillis())
            .setFollowRedirects(false);

    CompletableFuture<Incoming> result = new CompletableFuture<>();
    AtomicReference<@Nullable HttpClientRequest> sent = new AtomicReference<>();
    long max = out.maxBytes();
    http.request(options)
        .onComplete(
            connected -> {
              if (connected.failed()) {
                result.completeExceptionally(new Failure(HttpClientException.Kind.CONNECT, connected.cause()));
                return;
              }
              HttpClientRequest request = connected.result();
              sent.set(request);
              byte[] body = out.body();
              Future<HttpClientResponse> response =
                  body == null ? request.send() : request.send(Buffer.buffer(body));
              response.onComplete(
                  answered -> {
                    if (answered.failed()) {
                      result.completeExceptionally(new Failure(HttpClientException.Kind.IO, answered.cause()));
                      return;
                    }
                    read(answered.result(), request, max, result);
                  });
            });
    try {
      return result.get(out.timeout().toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      reset(sent.get());
      throw new Failure(HttpClientException.Kind.TIMEOUT, null);
    } catch (InterruptedException e) {
      reset(sent.get());
      throw e;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Failure f) {
        throw f;
      }
      throw new Failure(HttpClientException.Kind.IO, e.getCause());
    }
  }

  private static void read(
      HttpClientResponse response,
      HttpClientRequest request,
      long max,
      CompletableFuture<Incoming> result) {
    String length = response.getHeader("Content-Length");
    if (length != null) {
      try {
        if (Long.parseLong(length.trim()) > max) {
          result.completeExceptionally(new Failure(HttpClientException.Kind.RESPONSE_TOO_LARGE, null));
          request.reset();
          return;
        }
      } catch (NumberFormatException ignored) {
        // counted while reading instead
      }
    }
    Buffer received = Buffer.buffer();
    response.handler(
        chunk -> {
          if (result.isDone()) {
            return;
          }
          if ((long) received.length() + chunk.length() > max) {
            result.completeExceptionally(new Failure(HttpClientException.Kind.RESPONSE_TOO_LARGE, null));
            request.reset();
            return;
          }
          received.appendBuffer(chunk);
        });
    response.exceptionHandler(
        t -> result.completeExceptionally(new Failure(HttpClientException.Kind.IO, t)));
    response.endHandler(
        done -> {
          Map<String, List<String>> headers = new LinkedHashMap<>();
          for (String name : response.headers().names()) {
            headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(response.headers().getAll(name)));
          }
          result.complete(new Incoming(response.statusCode(), headers, received.getBytes()));
        });
  }

  private static void reset(@Nullable HttpClientRequest request) {
    if (request != null) {
      request.reset();
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      http.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      // closing is best effort
    }
  }
}
