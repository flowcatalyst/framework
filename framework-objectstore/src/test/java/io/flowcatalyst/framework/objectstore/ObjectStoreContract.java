package io.flowcatalyst.framework.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** What every object store driver must do; each driver's test extends this and supplies a store. */
public abstract class ObjectStoreContract {

  /**
   * A store with a size limit of at least 1 KiB, empty or private to this test (keys are unique per run).
   *
   * @return the store under test
   */
  protected abstract ObjectStore store();

  /**
   * The size limit of {@link #store()}, so the over-limit case can be tested.
   *
   * @return the limit in bytes
   */
  protected abstract int limit();

  private static String key(String tail) {
    return "contract-" + Long.toHexString(System.nanoTime()) + "/" + tail;
  }

  @Test
  public void putThenGetReturnsTheBytes() {
    ObjectStore s = store();
    String k = key("a.json");
    s.put(k, "{\"x\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(s.get(k)).isPresent();
    assertThat(new String(s.get(k).orElseThrow(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("{\"x\":1}");
  }

  @Test
  public void aMissingKeyIsEmptyNotAnError() {
    assertThat(store().get(key("nothing-here"))).isEmpty();
  }

  @Test
  public void aSecondPutReplacesTheFirstCompletely() {
    ObjectStore s = store();
    String k = key("r");
    s.put(k, new byte[] {1, 2, 3, 4, 5});
    s.put(k, new byte[] {9});
    assertThat(s.get(k)).contains(new byte[] {9});
  }

  @Test
  public void bytesAreKeptExactlyIncludingZerosAndInvalidUtf8() {
    ObjectStore s = store();
    String k = key("bin");
    byte[] all = new byte[256];
    for (int i = 0; i < 256; i++) {
      all[i] = (byte) i;
    }
    s.put(k, all);
    assertThat(s.get(k)).contains(all);
  }

  @Test
  public void emptyContentIsAValue() {
    ObjectStore s = store();
    String k = key("empty");
    s.put(k, new byte[0]);
    Optional<byte[]> got = s.get(k);
    assertThat(got).isPresent();
    assertThat(got.orElseThrow()).isEmpty();
  }

  @Test
  public void deleteRemovesAndIsIdempotent() {
    ObjectStore s = store();
    String k = key("d");
    s.put(k, new byte[] {1});
    s.delete(k);
    assertThat(s.get(k)).isEmpty();
    s.delete(k); // again: not an error
    s.delete(key("never-existed"));
  }

  @Test
  public void nestedKeysAreIndependentValues() {
    ObjectStore s = store();
    String base = key("n");
    s.put(base + "/one", new byte[] {1});
    s.put(base + "/two/three", new byte[] {2});
    assertThat(s.get(base + "/one")).contains(new byte[] {1});
    assertThat(s.get(base + "/two/three")).contains(new byte[] {2});
    s.delete(base + "/one");
    assertThat(s.get(base + "/two/three")).contains(new byte[] {2});
  }

  @Test
  public void aCallerCannotChangeAStoredValueAfterwards() {
    ObjectStore s = store();
    String k = key("alias");
    byte[] in = {1, 2, 3};
    s.put(k, in);
    in[0] = 99;
    byte[] out = s.get(k).orElseThrow();
    assertThat(out).containsExactly(1, 2, 3);
    out[1] = 99;
    assertThat(s.get(k).orElseThrow()).containsExactly(1, 2, 3);
  }

  @Test
  public void keysThatCouldEscapeOrAreMalformedAreRefused() {
    ObjectStore s = store();
    for (String bad :
        List.of("", "/abs", "a//b", "a/", "../x", "a/../b", "a/./b", ".hidden", "a/.hidden", "a b", "a\\b", "a\u0000b", "é")) {
      assertThatIllegalArgumentException().as("put '%s'", bad).isThrownBy(() -> s.put(bad, new byte[] {1}));
      assertThatIllegalArgumentException().as("get '%s'", bad).isThrownBy(() -> s.get(bad));
      assertThatIllegalArgumentException().as("delete '%s'", bad).isThrownBy(() -> s.delete(bad));
    }
    assertThatIllegalArgumentException().isThrownBy(() -> s.put("a".repeat(ObjectKeys.MAX_LENGTH + 1), new byte[] {1}));
  }

  @Test
  public void contentOverTheLimitIsRefusedAndNothingIsStored() {
    ObjectStore s = store();
    String k = key("big");
    assertThatIllegalArgumentException().isThrownBy(() -> s.put(k, new byte[limit() + 1]));
    assertThat(s.get(k)).isEmpty();
    s.put(k, new byte[limit()]);
    assertThat(s.get(k).orElseThrow()).hasSize(limit());
  }

  @Test
  public void concurrentPutsToOneKeyLeaveOneWholeValue() throws Exception {
    ObjectStore s = store();
    String k = key("race");
    int writers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<?>> done = new java.util.ArrayList<>();
      for (int w = 0; w < writers; w++) {
        byte fill = (byte) (w + 1);
        done.add(
            pool.submit(
                () -> {
                  go.await();
                  for (int i = 0; i < 20; i++) {
                    byte[] v = new byte[512];
                    java.util.Arrays.fill(v, fill);
                    s.put(k, v);
                  }
                  return null;
                }));
      }
      go.countDown();
      for (Future<?> f : done) {
        f.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
    byte[] result = s.get(k).orElseThrow();
    assertThat(result).hasSize(512);
    byte first = result[0];
    for (byte b : result) {
      assertThat(b).isEqualTo(first);
    }
  }
}
