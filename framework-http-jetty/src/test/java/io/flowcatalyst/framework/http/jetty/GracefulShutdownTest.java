package io.flowcatalyst.framework.http.jetty;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.sse.EventStreams;
import io.flowcatalyst.framework.http.sse.SseEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GracefulShutdownTest {

  @TempDir Path dir;

  private static boolean listening(int port) {
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress("127.0.0.1", port), 500);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static void await(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(10);
    }
  }

  @Test
  void anInFlightRequestFinishesWhileNewOnesAreRefused() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    App app =
        Apps.start(
            dir,
            Apps.env("HTTP_SHUTDOWN_GRACE", "20s"),
            r ->
                r.get(
                    "/slow",
                    req -> {
                      entered.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted before the drain ended");
                      }
                      return Response.text(200, "done");
                    }));
    int port = app.get(HttpServer.class).port();
    HttpClient client = HttpClient.newHttpClient();
    try (RawHttp keepAlive = new RawHttp(port)) {
      keepAlive.get("/health/live");
      assertThat(keepAlive.read().status()).isEqualTo(200); // the connection stays open

      CompletableFuture<HttpResponse<String>> inFlight =
          client.sendAsync(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/slow")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

      AtomicBoolean closed = new AtomicBoolean();
      Thread closer =
          Thread.ofPlatform()
              .start(
                  () -> {
                    app.close();
                    closed.set(true);
                  });

      await(() -> !listening(port), "the listener to close");
      assertThat(closed.get()).as("close() must wait for the in-flight request").isFalse();

      // a new request on the existing connection is refused with 503 and the connection closes
      keepAlive.get("/health/ready");
      RawHttp.Reply refused = keepAlive.read();
      assertThat(refused.status()).isEqualTo(503);
      assertThat(refused.headers().get("connection")).isEqualToIgnoringCase("close");
      assertThat(refused.body()).contains("\"error\":\"UNAVAILABLE\"");

      assertThat(closed.get()).isFalse();
      assertThat(inFlight.isDone()).isFalse();

      release.countDown();
      HttpResponse<String> done = inFlight.get(10, TimeUnit.SECONDS);
      assertThat(done.statusCode()).isEqualTo(200);
      assertThat(done.body()).isEqualTo("done");
      closer.join(10_000);
      assertThat(closed.get()).isTrue();
    }
  }

  @Test
  void readinessIsDownFromTheMomentShutdownBegins() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    App app =
        Apps.start(
            dir,
            Apps.env("HTTP_SHUTDOWN_GRACE", "20s"),
            r ->
                r.get(
                    "/slow",
                    req -> {
                      entered.countDown();
                      try {
                        release.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return Response.text(200, "done");
                    }));
    HttpServer server = app.get(HttpServer.class);
    int port = server.port();
    HttpClient client = HttpClient.newHttpClient();
    CompletableFuture<HttpResponse<String>> inFlight =
        client.sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/slow")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(server.isDraining()).isFalse();
    Thread closer = Thread.ofPlatform().start(app::close);
    await(server::isDraining, "draining to start");
    release.countDown();
    inFlight.get(10, TimeUnit.SECONDS);
    closer.join(10_000);
  }

  @Test
  void requestsStillRunningAfterTheGraceAreInterrupted() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    App app =
        Apps.start(
            dir,
            Apps.env("HTTP_SHUTDOWN_GRACE", "300ms"),
            r ->
                r.get(
                    "/stuck",
                    req -> {
                      entered.countDown();
                      try {
                        Thread.sleep(60_000);
                      } catch (InterruptedException e) {
                        interrupted.set(true);
                      }
                      return Response.text(200, "late");
                    }));
    int port = app.get(HttpServer.class).port();
    HttpClient client = HttpClient.newHttpClient();
    CompletableFuture<HttpResponse<String>> inFlight =
        client.sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stuck")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    long start = System.nanoTime();
    app.close();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(250), Duration.ofSeconds(8));
    await(interrupted::get, "the stuck handler to be interrupted");
    assertThat(listening(port)).isFalse();
    inFlight.completeOnTimeout(null, 5, TimeUnit.SECONDS).exceptionally(e -> null).get();
  }

  @Test
  void theGraceDefaultsToTheAppShutdownDrain() throws Exception {
    // app.shutdown.drain=300ms and no http.shutdown-grace: a stuck request is interrupted after ~300ms
    CountDownLatch entered = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    App app =
        Apps.start(
            dir,
            Apps.env("APP_SHUTDOWN_DRAIN", "300ms"),
            r ->
                r.get(
                    "/stuck",
                    req -> {
                      entered.countDown();
                      try {
                        Thread.sleep(60_000);
                      } catch (InterruptedException e) {
                        interrupted.set(true);
                      }
                      return Response.text(200, "late");
                    }));
    int port = app.get(HttpServer.class).port();
    CompletableFuture<?> inFlight =
        HttpClient.newHttpClient()
            .sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/stuck")).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    app.close();
    await(interrupted::get, "interrupt after the drain period");
    inFlight.completeOnTimeout(null, 5, TimeUnit.SECONDS).exceptionally(e -> null).get();
  }

  @Test
  void aBeforeDrainActionEndsALongLivedStreamSoTheDrainDoesNotWaitForTheGrace() throws Exception {
    CountDownLatch streaming = new CountDownLatch(1);
    AtomicBoolean stop = new AtomicBoolean();
    AtomicBoolean drainingInHook = new AtomicBoolean();
    AtomicBoolean listeningInHook = new AtomicBoolean();
    AtomicBoolean interrupted = new AtomicBoolean();
    App app =
        Apps.start(
            dir,
            Apps.env("HTTP_SHUTDOWN_GRACE", "8s"),
            r ->
                r.get(
                    "/events",
                    req ->
                        Response.stream(
                            200,
                            "text/event-stream",
                            out -> {
                              out.write(": open\n\n".getBytes(StandardCharsets.UTF_8));
                              out.flush();
                              streaming.countDown();
                              while (!stop.get()) {
                                try {
                                  Thread.sleep(10);
                                } catch (InterruptedException e) {
                                  interrupted.set(true);
                                  return;
                                }
                              }
                              out.write(": bye\n\n".getBytes(StandardCharsets.UTF_8));
                            })));
    HttpServer server = app.get(HttpServer.class);
    int port = server.port();
    server.beforeDrain(
        () -> {
          drainingInHook.set(server.isDraining());
          listeningInHook.set(listening(port));
          stop.set(true);
        });
    CompletableFuture<HttpResponse<String>> stream =
        HttpClient.newHttpClient()
            .sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/events")).build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(streaming.await(5, TimeUnit.SECONDS)).isTrue();
    long start = System.nanoTime();
    app.close();
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("the stream ended before the drain, not at the 8s grace")
        .isLessThan(Duration.ofSeconds(4));
    assertThat(interrupted.get()).isFalse();
    assertThat(drainingInHook.get()).as("the hook runs once shutdown has begun").isTrue();
    assertThat(listeningInHook.get()).as("and before the listener closes and the drain waits").isTrue();
    HttpResponse<String> done = stream.get(5, TimeUnit.SECONDS);
    assertThat(done.body()).isEqualTo(": open\n\n: bye\n\n");
  }

  @Test
  void theModulesEventStreamsEndWhenShutdownBeginsAndUseTheConfiguredHeartbeat() throws Exception {
    AtomicReference<EventStreams> streams = new AtomicReference<>();
    App app =
        Apps.start(
            dir,
            Apps.env("HTTP_SHUTDOWN_GRACE", "8s", "HTTP_SSE_HEARTBEAT", "50ms"),
            r -> r.get("/events", req -> streams.get().serve(req, e -> true)));
    streams.set(app.get(EventStreams.class));
    int port = app.get(HttpServer.class).port();
    CompletableFuture<HttpResponse<String>> stream =
        HttpClient.newHttpClient()
            .sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/events")).build(),
                HttpResponse.BodyHandlers.ofString());
    await(() -> streams.get().open() == 1, "the stream to open");
    streams.get().publish(new SseEvent(1, "hello", "{}"));
    Thread.sleep(200); // a few heartbeats
    long start = System.nanoTime();
    app.close();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    String body = stream.get(5, TimeUnit.SECONDS).body();
    assertThat(body).startsWith("retry: 3000\n\nid: 1\nevent: hello\ndata: {}\n\n: ping\n\n");
  }

  @Test
  void aFailingBeforeDrainActionDoesNotStopTheOthersAndEachRunsOnce() throws Exception {
    App app = Apps.start(dir, Apps.env(), r -> {});
    HttpServer server = app.get(HttpServer.class);
    List<String> ran = new CopyOnWriteArrayList<>();
    server.beforeDrain(() -> ran.add("first"));
    server.beforeDrain(
        () -> {
          throw new IllegalStateException("boom");
        });
    server.beforeDrain(() -> ran.add("third"));
    app.close();
    server.stop(); // a second stop (the module's shutdown ran once) runs nothing again
    assertThat(ran).containsExactly("first", "third");
  }

  @Test
  void closeIsIdempotentAndAnIdleServerStopsImmediately() throws Exception {
    App app = Apps.start(dir, Apps.env(), r -> {});
    long start = System.nanoTime();
    app.close();
    app.close();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }
}
