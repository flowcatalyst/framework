package io.flowcatalyst.framework.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Background;
import io.flowcatalyst.framework.core.LoopGate;
import io.flowcatalyst.framework.core.LoopOptions;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.MetricsSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link LockGate}: a background loop's run only while holding a lock (section 1). */
class LockGateTest {

  @TempDir Path dir;

  private final AdjustableClock clock = new AdjustableClock();
  private final MemoryLockDriver driver = new MemoryLockDriver(clock::nanos);
  private final LockStore store = new LockStore(driver, clock);
  private final LockKey key = LockKey.service("sweeper");

  @Test
  void aFreeLockRunsTheWorkWithItsFencingTokenAndReleasesIt() throws Exception {
    List<Long> tokens = new ArrayList<>();
    LoopGate.Result r = LockGate.of(store, key, Duration.ofSeconds(5)).enter("sweeper", tokens::add);
    assertThat(r).isEqualTo(new LoopGate.Ran());
    assertThat(tokens).hasSize(1);
    assertThat(tokens.get(0)).isPositive();
    assertThat(store.tryAcquire(key, Duration.ofSeconds(1))).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void aHeldLockSkipsTheRunWithoutWaiting() throws Exception {
    store.tryAcquire(key, Duration.ofSeconds(30));
    AtomicBoolean ran = new AtomicBoolean();
    long before = System.nanoTime();
    LoopGate.Result r =
        LockGate.of(store, key, Duration.ofSeconds(5)).enter("sweeper", t -> ran.set(true));
    assertThat(System.nanoTime() - before).isLessThan(TimeUnit.MILLISECONDS.toNanos(500));
    assertThat(r).isInstanceOf(LoopGate.Skipped.class);
    assertThat(((LoopGate.Skipped) r).reason()).contains("svc:sweeper");
    assertThat(ran).isFalse();
  }

  @Test
  void aLockLostWhileRunningIsLostAndTheWorkIsInterrupted() throws Exception {
    AtomicBoolean interrupted = new AtomicBoolean();
    LoopGate.Result r =
        LockGate.of(store, key, Duration.ofMillis(300))
            .enter(
                "sweeper",
                token -> {
                  driver.expireNow(key.stored());
                  store.tryAcquire(key, Duration.ofSeconds(30)); // another replica takes it
                  try {
                    Thread.sleep(Duration.ofSeconds(5));
                  } catch (InterruptedException e) {
                    interrupted.set(true);
                  }
                });
    assertThat(r).isEqualTo(new LoopGate.Lost());
    assertThat(interrupted).isTrue();
  }

  @Test
  void theTasksOwnExceptionComesOutUnwrapped() {
    LockGate gate = LockGate.of(store, key, Duration.ofSeconds(5));
    assertThatThrownBy(() -> gate.enter("x", t -> { throw new IOException("disk"); }))
        .isInstanceOf(IOException.class)
        .hasMessage("disk");
    assertThatThrownBy(() -> gate.enter("x", t -> { throw new IllegalStateException("state"); }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("state");
    // the lock was released both times
    assertThat(store.tryAcquire(key, Duration.ofSeconds(1))).isInstanceOf(AcquireResult.Acquired.class);
  }

  @Test
  void theLeaseIsChecked() {
    assertThatIllegalArgumentException().isThrownBy(() -> LockGate.of(store, key, Duration.ofMillis(99)));
    assertThatIllegalArgumentException().isThrownBy(() -> LockGate.of(store, key, Duration.ofHours(25)));
  }

  @Test
  void twoReplicasOfALoopNeverRunTogether() throws Exception {
    Files.writeString(
        dir.resolve("application.yaml"),
        """
        locks:
          instances:
            local: { driver: memory }
        """);
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    List<String> who = Collections.synchronizedList(new ArrayList<>());
    Module replicas =
        new Module() {
          @Override
          public String id() {
            return "replicas";
          }

          @Override
          public void boot(App app) {
            Background bg = app.get(Background.class);
            for (String name : List.of("replica-a", "replica-b")) {
              bg.loop(
                  name,
                  LoopOptions.every(Duration.ofMillis(10))
                      .gatedBy(LockGate.service(app.get(Locks.class), "sweeper", Duration.ofSeconds(5))),
                  tick -> {
                    assertThat(tick.fencingToken()).isPresent();
                    int now = concurrent.incrementAndGet();
                    maxConcurrent.accumulateAndGet(now, Math::max);
                    who.add(tick.loop());
                    Thread.sleep(15);
                    concurrent.decrementAndGet();
                  });
            }
          }
        };
    try (App app =
        App.builder()
            .configDirectory(dir)
            .environment(Map.of())
            .module(new LocksModule())
            .module(replicas)
            .start()) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (skipped(app) < 3 || who.size() < 10) {
        assertThat(System.nanoTime()).isLessThan(deadline);
        Thread.sleep(10);
      }
    }
    assertThat(maxConcurrent).hasValue(1);
  }

  private static double skipped(App app) {
    double total = 0;
    for (MetricsSnapshot.Family f : app.get(Metrics.class).snapshot().families()) {
      if (f.name().equals("fw_background_runs_total")) {
        for (MetricsSnapshot.Sample s : f.samples()) {
          if (s.labelValues().get(1).equals("skipped")) {
            total += s.value();
          }
        }
      }
    }
    return total;
  }
}
