package io.flowcatalyst.framework.http.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.flowcatalyst.framework.http.Method;
import io.flowcatalyst.framework.http.RawRequest;
import io.flowcatalyst.framework.http.Request;
import io.flowcatalyst.framework.http.Response;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import io.flowcatalyst.framework.http.TestResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20) // a stream that never ends fails the test instead of hanging the build
class EventStreamsTest {

  private static final String RETRY = "retry: 3000\n\n";

  private static SseEvent event(long id, String data) {
    return new SseEvent(id, "order-changed", data);
  }

  private static String wire(long id, String data) {
    return "id: " + id + "\nevent: order-changed\ndata: " + data + "\n\n";
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(5);
    }
  }

  /** A route at /events serving the streams, sent from another thread (the body runs until the stream ends). */
  private static CompletableFuture<TestResponse> open(
      EventStreams streams, EventStreams.Replay replay, Map<String, String> headers, String target) {
    Routes routes = new Routes();
    routes.get("/events", r -> streams.serve(r, e -> !e.data().contains("other"), replay));
    TestClient client = TestClient.of(routes);
    return CompletableFuture.supplyAsync(
        () -> {
          TestClient.Call call = client.get(target);
          headers.forEach(call::header);
          return call.send();
        },
        r -> Thread.ofVirtual().start(r));
  }

  private static Request request(Map<String, List<String>> headers) {
    return Request.of(
        new RawRequest(Method.GET, "/events", null, headers, InputStream.nullInputStream(), -1, 1024, "HTTP/1.1"));
  }

  @Test
  void aReconnectingStreamGetsWhatItMissedThenTheLiveEventsEachOnceAndInOrder() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofSeconds(30));
    AtomicLong askedAfter = new AtomicLong(-1);
    EventStreams.Replay replay =
        (after, limit) -> {
          askedAfter.set(after);
          assertThat(limit).isEqualTo(EventStreams.REPLAY_LIMIT);
          // events 8 and 9 are published while the replay reads: the stream has already subscribed,
          // so both wait there; 8 is skipped because the replay sends it, 9 is sent live
          streams.publish(event(8, "{\"n\":8}"));
          streams.publish(event(9, "{\"n\":9}"));
          return List.of(event(7, "{\"n\":7}"), event(8, "{\"n\":8}"));
        };
    CompletableFuture<TestResponse> response = open(streams, replay, Map.of("Last-Event-ID", "6"), "/events");
    await(() -> askedAfter.get() >= 0 && streams.open() == 1, "the stream to subscribe");
    streams.publish(event(10, "{\"n\":10,\"other\":true}")); // the filter refuses it
    streams.publish(event(11, "{\"n\":11}"));
    await(() -> streams.open() == 1, "still open");
    streams.closeAll();
    TestResponse r = response.get(5, TimeUnit.SECONDS);

    assertThat(askedAfter.get()).isEqualTo(6);
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.header("Content-Type")).isEqualTo("text/event-stream");
    assertThat(r.header("Cache-Control")).isEqualTo("no-cache");
    assertThat(r.header("X-Accel-Buffering")).isEqualTo("no");
    assertThat(r.body())
        .isEqualTo(
            RETRY + wire(7, "{\"n\":7}") + wire(8, "{\"n\":8}") + wire(9, "{\"n\":9}") + wire(11, "{\"n\":11}"));
    assertThat(streams.open()).isZero();
  }

  @Test
  void theLastEventIdQueryParameterServesClientsThatCannotSetTheHeader() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofSeconds(30));
    AtomicLong askedAfter = new AtomicLong(-1);
    CompletableFuture<TestResponse> response =
        open(
            streams,
            (after, limit) -> {
              askedAfter.set(after);
              return List.of();
            },
            Map.of(),
            "/events?lastEventId=41");
    await(() -> streams.open() == 1, "the stream to subscribe");
    streams.closeAll();
    response.get(5, TimeUnit.SECONDS);
    assertThat(askedAfter.get()).isEqualTo(41);
  }

  @Test
  void withoutALastEventIdTheStreamStartsLiveAndAnUnreadableOneCountsAsNone() throws Exception {
    for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("Last-Event-ID", "x7"), Map.of("Last-Event-ID", "-3"))) {
      EventStreams streams = new EventStreams(Duration.ofSeconds(30));
      AtomicInteger replays = new AtomicInteger();
      CompletableFuture<TestResponse> response =
          open(
              streams,
              (after, limit) -> {
                replays.incrementAndGet();
                return List.of(event(1, "old"));
              },
              headers,
              "/events");
      await(() -> streams.open() == 1, "the stream to subscribe");
      streams.publish(event(2, "new"));
      streams.closeAll();
      assertThat(response.get(5, TimeUnit.SECONDS).body()).as(headers.toString()).isEqualTo(RETRY + wire(2, "new"));
      assertThat(replays.get()).isZero();
    }
  }

  @Test
  void liveEventsAtOrBelowTheReplaysLastIdAreNotSentAgain() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofSeconds(30));
    CompletableFuture<TestResponse> response =
        open(streams, (after, limit) -> List.of(event(5, "five")), Map.of("Last-Event-ID", "4"), "/events");
    await(() -> streams.open() == 1, "the stream to subscribe");
    streams.publish(event(3, "three"));
    streams.publish(event(5, "five"));
    streams.publish(event(6, "six"));
    streams.closeAll();
    assertThat(response.get(5, TimeUnit.SECONDS).body()).isEqualTo(RETRY + wire(5, "five") + wire(6, "six"));
  }

  @Test
  void anIdleStreamSendsHeartbeats() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofMillis(20));
    CompletableFuture<TestResponse> response = open(streams, (a, l) -> List.of(), Map.of(), "/events");
    await(() -> streams.open() == 1, "the stream to subscribe");
    Thread.sleep(150);
    streams.closeAll();
    String body = response.get(5, TimeUnit.SECONDS).body();
    assertThat(body).startsWith(RETRY + ": ping\n\n: ping\n\n");
    assertThat(body.replace(": ping\n\n", "")).isEqualTo(RETRY);
  }

  @Test
  void multiLineDataIsOneDataLinePerLine() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofSeconds(30));
    CompletableFuture<TestResponse> response = open(streams, (a, l) -> List.of(), Map.of(), "/events");
    await(() -> streams.open() == 1, "the stream to subscribe");
    streams.publish(new SseEvent(1, "note", "a\nb\r\nc"));
    streams.closeAll();
    assertThat(response.get(5, TimeUnit.SECONDS).body())
        .isEqualTo(RETRY + "id: 1\nevent: note\ndata: a\ndata: b\ndata: c\n\n");
  }

  @Test
  void aStreamOpenedAfterCloseAllEndsAtOnce() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofSeconds(30));
    streams.closeAll();
    TestResponse r = open(streams, (a, l) -> List.of(event(2, "missed")), Map.of("Last-Event-ID", "1"), "/events")
        .get(5, TimeUnit.SECONDS);
    assertThat(r.body()).isEqualTo(RETRY);
    assertThat(streams.open()).isZero();
  }

  @Test
  void aFailingReplayEndsTheStreamSoTheClientReconnects() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofSeconds(30));
    TestResponse r =
        open(
                streams,
                (a, l) -> {
                  throw new IllegalStateException("database away");
                },
                Map.of("Last-Event-ID", "1"),
                "/events")
            .get(5, TimeUnit.SECONDS);
    assertThat(r.body()).isEqualTo(RETRY);
    assertThat(streams.open()).isZero();
  }

  @Test
  void aStreamThatFallsBehindIsSentWhatItHeldThenEnds() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofSeconds(30));
    CountDownLatch firstWrite = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    OutputStream slow =
        new OutputStream() {
          @Override
          public void write(int b) {
            bytes.write(b);
          }

          @Override
          public void write(byte[] b, int off, int len) {
            bytes.write(b, off, len);
          }

          @Override
          public void flush() {
            firstWrite.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    Response response = streams.serve(request(Map.of()), e -> true);
    CompletableFuture<Boolean> written =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return response.writeBody(slow);
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            },
            r -> Thread.ofVirtual().start(r));
    assertThat(firstWrite.await(5, TimeUnit.SECONDS)).isTrue(); // the retry line; the stream is subscribed
    await(() -> streams.open() == 1, "the stream to subscribe");
    for (int i = 1; i <= EventStreams.BUFFER + 1; i++) {
      streams.publish(event(i, Integer.toString(i)));
    }
    assertThat(streams.open()).as("one event more than the buffer drops the stream").isZero();
    release.countDown();
    assertThat(written.get(5, TimeUnit.SECONDS)).isTrue();
    String body = bytes.toString(StandardCharsets.UTF_8);
    assertThat(body).endsWith(wire(EventStreams.BUFFER, Integer.toString(EventStreams.BUFFER)));
    assertThat(body).doesNotContain("id: " + (EventStreams.BUFFER + 1) + "\n");
  }

  @Test
  void aClientThatGoesAwayIsUnsubscribed() throws Exception {
    EventStreams streams = new EventStreams(Duration.ofMillis(20));
    List<String> writes = new ArrayList<>();
    OutputStream gone =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            if (!writes.isEmpty()) {
              throw new IOException("broken pipe");
            }
            writes.add(new String(b, off, len, StandardCharsets.UTF_8));
          }
        };
    Response response = streams.serve(request(Map.of()), e -> true);
    assertThat(response.writeBody(gone)).as("the heartbeat finds the client gone").isFalse();
    assertThat(writes).containsExactly(RETRY);
    assertThat(streams.open()).isZero();
  }

  @Test
  void anEventNeedsAPositiveIdAndAOneLineType() {
    assertThatIllegalArgumentException().isThrownBy(() -> new SseEvent(0, "t", "d"));
    assertThatIllegalArgumentException().isThrownBy(() -> new SseEvent(1, "a\nb", "d"));
    assertThatIllegalArgumentException().isThrownBy(() -> new SseEvent(1, " ", "d"));
    assertThatIllegalArgumentException().isThrownBy(() -> new EventStreams(Duration.ZERO));
  }
}
