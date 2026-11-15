package io.flowcatalyst.framework.partitions;

/** The TSID text form (write-path contract section 8): 13 Crockford base-32 characters. */
final class Tsid {

  private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  private static final int LENGTH = 13;

  private Tsid() {}

  /** The text of a TSID value: five bits per character, most significant first, zero-padded. */
  static String encode(long value) {
    char[] out = new char[LENGTH];
    long v = value;
    for (int i = LENGTH - 1; i >= 0; i--) {
      out[i] = ALPHABET.charAt((int) (v & 0x1F));
      v >>>= 5;
    }
    return new String(out);
  }
}
