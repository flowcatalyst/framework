package io.flowcatalyst.framework.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IdsTest {

  /** A clock the test moves by hand. */
  private static final class Manual implements InstantSource {
    final AtomicLong millis;

    Manual(long millis) {
      this.millis = new AtomicLong(millis);
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }
  }

  @Test
  void shapeIsThirteenCrockfordCharactersWithOptionalPrefix() {
    Ids ids = new Ids(InstantSource.system());
    String raw = ids.generate();
    assertThat(raw).matches("[0-9A-HJKMNP-TV-Z]{13}");
    assertThat(Ids.isValid(raw)).isTrue();
    assertThat(ids.generate("ord")).matches("ord_[0-9A-HJKMNP-TV-Z]{13}");
    assertThat(Ids.isValid("short")).isFalse();
    assertThat(Ids.isValid("0000000000UUU")).isFalse();
  }

  @Test
  void prefixMustBeLowercaseAlphanumeric() {
    Ids ids = new Ids(InstantSource.system());
    for (String bad : new String[] {"", "Ord", "o_d", "1ord", "o d"}) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> ids.generate(bad));
    }
  }

  @Test
  void timestampIsInTheTopBits() {
    Manual clock = new Manual(1_700_000_000_123L);
    long value = Ids.toLong(new Ids(clock).generate()).orElseThrow();
    assertThat(value >>> 22).isEqualTo(1_700_000_000_123L);
  }

  @Test
  void aFrozenClockStillGivesStrictlyIncreasingIds() {
    Ids ids = new Ids(new Manual(1_700_000_000_000L));
    String previous = ids.generate();
    for (int i = 0; i < 10_000; i++) {
      String next = ids.generate();
      assertThat(next).isGreaterThan(previous);
      previous = next;
    }
  }

  @Test
  void aBackwardsClockNeverRepeatsOrGoesBack() {
    Manual clock = new Manual(1_700_000_000_000L);
    Ids ids = new Ids(clock);
    String previous = ids.generate();
    for (int i = 0; i < 5_000; i++) {
      clock.millis.addAndGet(i % 7 == 0 ? -50 : 1);
      String next = ids.generate();
      assertThat(next).isGreaterThan(previous);
      previous = next;
    }
  }

  @Test
  void moreThan4096IdsInOneMillisecondBorrowTheNextOne() {
    Manual clock = new Manual(1_700_000_000_000L);
    Ids ids = new Ids(clock);
    Set<String> seen = new HashSet<>();
    String previous = "";
    for (int i = 0; i < 9_000; i++) {
      String next = ids.generate();
      assertThat(next).isGreaterThan(previous);
      assertThat(seen.add(next)).isTrue();
      previous = next;
    }
  }

  @Test
  void thirtyTwoVirtualThreadsGetNoDuplicatesAndEachSeesIncreasingIds() throws Exception {
    Ids ids = new Ids(InstantSource.system());
    int threads = 32;
    int perThread = 20_000;
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<List<String>>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(
            pool.submit(
                () -> {
                  List<String> mine = new ArrayList<>(perThread);
                  for (int i = 0; i < perThread; i++) {
                    mine.add(ids.generate());
                  }
                  return mine;
                }));
      }
      Set<String> all = new HashSet<>();
      for (Future<List<String>> f : futures) {
        List<String> mine = f.get();
        for (int i = 1; i < mine.size(); i++) {
          assertThat(mine.get(i)).isGreaterThan(mine.get(i - 1));
        }
        all.addAll(mine);
      }
      assertThat(all).hasSize(threads * perThread);
    }
  }

  @Test
  void theFrameworkProvidesIdsFromTheInjectedClock() {
    try (App app =
        App.builder()
            .configDirectory(java.nio.file.Path.of("no-such-dir"))
            .environment(java.util.Map.of())
            .instantSource(new Manual(1_700_000_000_000L))
            .start()) {
      long value = Ids.toLong(app.get(Ids.class).generate()).orElseThrow();
      assertThat(value >>> 22).isEqualTo(1_700_000_000_000L);
    }
  }
}
