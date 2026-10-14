package io.flowcatalyst.framework.http.jetty;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.http.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // a 300ms shutdown drain and no grace of its own: a stuck request is interrupted after ~300ms
    CountDownLatch entered = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    App app =
        Apps.start(
            dir,
            Apps.env(),
            b -> b.shutdownDrain(Duration.ofMillis(300)),
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
  void closeIsIdempotentAndAnIdleServerStopsImmediately() throws Exception {
    App app = Apps.start(dir, Apps.env(), r -> {});
    long start = System.nanoTime();
    app.close();
    app.close();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
  }
}
