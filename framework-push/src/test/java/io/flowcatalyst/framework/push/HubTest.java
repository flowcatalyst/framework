package io.flowcatalyst.framework.push;

import static org.assertj.core.api.Assertions.assertThat;

import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The hub on its own, over a log in memory (push contract sections 7 and 8, amendment 11). */
class HubTest {

  static final Subscription ORDER = new Subscription(List.of("test:order:1"), "acme", "", "", "");

  final Metrics registry = Metrics.memory(100, () -> 0);

  Hub hub(Log log, int buffer, long pollMillis) {
    return new Hub(log, new PushMetrics(registry), new Hub.Config(buffer, 10_000, 1 << 30, 1 << 30, pollMillis));
  }

  double counter(String name, String... labels) {
    for (MetricsSnapshot.Family f : registry.snapshot().families()) {
      if (f.name().equals(name)) {
        for (MetricsSnapshot.Sample s : f.samples()) {
          if (s.labelValues().equals(List.of(labels))) {
            return s.value();
          }
        }
      }
    }
    return 0;
  }

  static PushStream open(Hub hub, Subscription sub) {
    return ((Hub.Ok) hub.open(sub, false, 0, false)).stream();
  }

  /** Every read asks for at most a page, and the replay is complete. */
  @Test
  void aLogReplayReadsOnePageAtATime() throws Exception {
    MemLog log = new MemLog().fill(3 * Hub.PAGE_SIZE + 500, 4, true); // longer than any memory
    Hub hub = hub(log, 256, 3_600_000);
    PushStream s = open(hub, ORDER);
    List<Long> got = new ArrayList<>();
    boolean reset = hub.replayLog(s, 0, log.entries.size(), e -> got.add(e.id()));
    assertThat(reset).isFalse();
    assertThat(got).hasSize(log.entries.size() / 4);
    assertThat(got.getFirst()).isEqualTo(1);
    assertThat(got.getLast()).isEqualTo(3497);
    assertThat(log.reads()).hasSize(4).allSatisfy(limit -> assertThat(limit).isLessThanOrEqualTo(Hub.PAGE_SIZE));
  }

  /** Streams that resume from the same id at the same time share each page read. */
  @Test
  void concurrentReplaysShareTheirReads() throws Exception {
    MemLog log = new MemLog().fill(40, 2, false);
    log.hold = new CountDownLatch(1);
    Hub hub = hub(log, 256, 3_600_000);
    int streams = 20;
    AtomicInteger[] counts = new AtomicInteger[streams];
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < streams; i++) {
      counts[i] = new AtomicInteger();
      PushStream s = open(hub, ORDER);
      AtomicInteger count = counts[i];
      threads.add(
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      hub.replayLog(s, 10, 40, e -> count.incrementAndGet());
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
    }
    assertThat(log.reading.take()).isTrue();
    Thread.sleep(150); // the others reach the read under way
    log.hold.countDown();
    for (Thread t : threads) {
      t.join();
    }
    assertThat(log.reads()).as("one read shared by the replays").hasSize(1);
    for (AtomicInteger c : counts) {
      assertThat(c.get()).isEqualTo(15);
    }
  }

  /** The drain ends every stream at once, with the reason drain, and opens none after it. */
  @Test
  void theDrainEndsEveryStreamAtOnce() {
    Hub hub = hub(new MemLog(), 256, 3_600_000);
    int streams = 50_000;
    List<PushStream> all = new ArrayList<>(streams);
    for (int i = 0; i < streams; i++) {
      all.add(open(hub, new Subscription(List.of("test:order:" + i), "acme", "prn_1", "", "")));
    }
    long start = System.nanoTime();
    hub.drain();
    long millis = (System.nanoTime() - start) / 1_000_000;
    for (PushStream s : all) {
      assertThat(s.isEnded()).isTrue();
      assertThat(s.reason()).isEqualTo(Hub.END_DRAIN);
    }
    assertThat(millis).as("the drain of %d streams", streams).isLessThan(2000);
    assertThat(hub.openCount()).isZero();
    assertThat(counter("fw_push_streams_ended_total", "drain")).isEqualTo(streams);
    assertThat(counter("fw_push_streams_open")).isZero();
    assertThat(hub.open(ORDER, false, 0, false)).isEqualTo(new Hub.Refused(true));
  }

  /** A drained hub gives the database no more work, and the replay of an ended stream stops. */
  @Test
  void aDrainedHubStopsReadingTheLog() throws Exception {
    MemLog log = new MemLog().fill(10, 2, false);
    Hub hub = hub(log, 256, 10);
    hub.start();
    try {
      TestNode.await("the hub to read the log", () -> hub.lastId() == 10);
      PushStream s = open(hub, ORDER);
      hub.drain();
      int before = log.reads().size();
      Thread.sleep(150); // many polls
      hub.poke();
      Thread.sleep(30);
      assertThat(log.reads()).hasSize(before);
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> hub.replayLog(s, 0, 10, e -> {})).isInstanceOf(Hub.StreamEnded.class);
      assertThat(log.reads()).as("the replay of an ended stream reads nothing").hasSize(before);
    } finally {
      hub.stop(10_000);
    }
  }

  /** Stopping the hub lets a read of the log under way finish instead of interrupting it. */
  @Test
  void stoppingTheHubLetsALogReadFinish() throws Exception {
    MemLog log = new MemLog();
    log.hold = new CountDownLatch(1);
    Hub hub = hub(log, 256, 3_600_000);
    hub.start();
    assertThat(log.reading.take()).isTrue();
    Thread stopper = Thread.ofVirtual().start(() -> hub.stop(10_000));
    Thread.sleep(100);
    log.hold.countDown();
    stopper.join(10_000);
    assertThat(stopper.isAlive()).as("the hub stopped").isFalse();
    assertThat(log.interrupted).as("the read under way was interrupted by the stop").isFalse();
  }

  /** A push is encoded once and the same frame is handed to every stream; a caught-up stream holds no queue. */
  @Test
  void aPushIsEncodedOnceForEveryStream() {
    Hub hub = hub(new MemLog(), 256, 3_600_000);
    List<PushStream> streams = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      streams.add(open(hub, ORDER));
    }
    List<Entry> page = new ArrayList<>();
    for (int i = 1; i <= 100; i++) {
      page.add(new Entry(i, "test:order:1", "order.updated", "acme", "{}", TestNode.BEGIN));
    }
    hub.dispatch(Hub.framesOf(page));
    List<Frame> first = new ArrayList<>();
    for (Frame f = streams.get(0).next(); f != null; f = streams.get(0).next()) {
      first.add(f);
    }
    assertThat(first).hasSize(100);
    assertThat(first.getFirst().wire()).isEqualTo(Wire.pushEvent(page.getFirst()));
    for (PushStream s : streams.subList(1, 3)) {
      for (int i = 0; i < 100; i++) {
        assertThat(s.next()).as("frame %d is the shared one", i).isSameAs(first.get(i));
      }
    }
    assertThat(streams.get(0).hasPushes()).isFalse();
  }

  /** A stream whose push is a log entry twice (A2 duplicates) gets it once. */
  @Test
  void aRowLoggedTwiceIsDeliveredOnce() {
    Hub hub = hub(new MemLog(), 256, 3_600_000);
    PushStream s = open(hub, ORDER);
    hub.dispatch(
        Hub.framesOf(
            List.of(
                new Entry(1, "test:order:1", "a", "acme", "{}", TestNode.BEGIN, 7),
                new Entry(2, "test:order:1", "a", "acme", "{}", TestNode.BEGIN, 7),
                new Entry(3, "test:order:1", "a", "acme", "{}", TestNode.BEGIN, 8))));
    List<Long> ids = new ArrayList<>();
    for (Frame f = s.next(); f != null; f = s.next()) {
      ids.add(f.id());
    }
    assertThat(ids).containsExactly(1L, 3L);
  }
}
