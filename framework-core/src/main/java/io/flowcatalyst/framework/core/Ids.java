package io.flowcatalyst.framework.core;

import java.time.InstantSource;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Generates TSIDs: 13 Crockford base-32 characters, time-ordered, optionally prefixed
 * ({@code ord_0ABC...}). Provided by the framework itself; the time comes from the injected
 * {@link InstantSource}.
 *
 * <p>Layout, the same as the FlowCatalyst platform's generator (so ids sort alike):
 *
 * <pre>
 * bits 63..22  milliseconds since the Unix epoch (42 bits)
 * bits 21..10  sequence (12 bits)
 * bits 9..0    random (10 bits)
 * </pre>
 *
 * <p>Monotonic within one {@code Ids}: the last issued (millisecond, sequence) pair only moves
 * forward, by compare-and-set. When the clock does not advance or goes backwards the sequence
 * continues; when 4096 ids are issued within one millisecond the next millisecond is borrowed.
 * So ids from one instance are unique and strictly increasing, on any number of threads.
 */
public final class Ids {

  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final int LENGTH = 13;
  private static final long MS_MASK = 0x3FF_FFFF_FFFFL;
  private static final int SEQ_BITS = 12;
  private static final int SEQ_MASK = 0xFFF;
  private static final int RANDOM_BITS = 10;
  private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9]*");

  private static final byte[] DECODE = decodeTable();

  private final InstantSource clock;

  /** Bits 63..12 hold the last issued millisecond, bits 11..0 the last issued sequence. */
  private final AtomicLong state = new AtomicLong();

  /**
   * Creates a generator.
   *
   * @param clock the wall-clock source
   */
  public Ids(InstantSource clock) {
    this.clock = clock;
  }

  /**
   * A raw TSID.
   *
   * @return 13 Crockford base-32 characters
   */
  public String generate() {
    long now = clock.millis();
    while (true) {
      long old = state.get();
      long lastMs = old >>> SEQ_BITS;
      long lastSeq = old & SEQ_MASK;
      long ms;
      long seq;
      if (now > lastMs) {
        ms = now;
        seq = ThreadLocalRandom.current().nextInt(SEQ_MASK + 1);
      } else if (lastSeq < SEQ_MASK) {
        ms = lastMs;
        seq = lastSeq + 1;
      } else {
        ms = lastMs + 1;
        seq = ThreadLocalRandom.current().nextInt(SEQ_MASK + 1);
      }
      if (state.compareAndSet(old, (ms << SEQ_BITS) | seq)) {
        long random = ThreadLocalRandom.current().nextInt(1 << RANDOM_BITS);
        return encode(((ms & MS_MASK) << (SEQ_BITS + RANDOM_BITS)) | (seq << RANDOM_BITS) | random);
      }
    }
  }

  /**
   * A prefixed TSID, {@code prefix_raw}.
   *
   * @param prefix lowercase letters and digits, starting with a letter
   * @return the prefixed id
   * @throws IllegalArgumentException when the prefix is not of that form
   */
  public String generate(String prefix) {
    if (!PREFIX.matcher(prefix).matches()) {
      throw new IllegalArgumentException("TSID prefix must match [a-z][a-z0-9]*: " + prefix);
    }
    return prefix + "_" + generate();
  }

  /**
   * Whether a string is a well-formed raw (unprefixed) TSID.
   *
   * @param text the text
   * @return true for 13 Crockford base-32 characters
   */
  public static boolean isValid(String text) {
    return decode(text).isPresent();
  }

  /**
   * The numeric value of a raw TSID.
   *
   * @param raw 13 Crockford base-32 characters
   * @return the value, or empty when the text is not a TSID
   */
  public static OptionalLong toLong(String raw) {
    return decode(raw);
  }

  private static String encode(long value) {
    char[] out = new char[LENGTH];
    long v = value;
    for (int i = LENGTH - 1; i >= 0; i--) {
      out[i] = ALPHABET.charAt((int) (v & 0x1F));
      v >>>= 5;
    }
    return new String(out);
  }

  private static OptionalLong decode(String raw) {
    if (raw.length() != LENGTH) {
      return OptionalLong.empty();
    }
    long v = 0;
    for (int i = 0; i < LENGTH; i++) {
      char c = Character.toUpperCase(raw.charAt(i));
      int digit = c < DECODE.length ? DECODE[c] : -1;
      if (digit < 0) {
        return OptionalLong.empty();
      }
      v = (v << 5) | digit;
    }
    return OptionalLong.of(v);
  }

  private static byte[] decodeTable() {
    byte[] table = new byte[128];
    Arrays.fill(table, (byte) -1);
    for (int i = 0; i < ALPHABET.length(); i++) {
      table[ALPHABET.charAt(i)] = (byte) i;
    }
    return table;
  }
}
