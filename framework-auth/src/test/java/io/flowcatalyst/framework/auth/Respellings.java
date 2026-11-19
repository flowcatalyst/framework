package io.flowcatalyst.framework.auth;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The other spellings of a value made of base64url parts separated by dots (token-spelling
 * contract, section 3), and the changes that certainly alter what it carries. Both refuse to pass
 * by chance: each case differs from the value, and each other last character is checked to decode,
 * leniently, to exactly the value's bytes. Used by the spelling tests of the auth and push modules.
 */
public final class Respellings {

  /** The base64url alphabet, in value order. */
  public static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

  /**
   * One changed value.
   *
   * @param name what was changed
   * @param value the changed value
   */
  public record Spelling(String name, String value) {}

  private Respellings() {}

  /**
   * How many low bits of the last character of an unpadded base64url string carry nothing.
   *
   * @param length the string's length
   * @return 0, 4 (2 mod 4) or 2 (3 mod 4); -1 for a length of 1 mod 4, which is not base64
   */
  public static int spareBits(int length) {
    return switch (length % 4) {
      case 0 -> 0;
      case 2 -> 4;
      case 3 -> 2;
      default -> -1;
    };
  }

  /**
   * Every string that differs from a canonical part only in its last character and decodes,
   * leniently, to the same bytes: 2^spareBits - 1 of them.
   *
   * @param part a canonical base64url part
   * @return the other spellings, in the order of their last character's value
   */
  public static List<String> lastCharacterSpellings(String part) {
    int spare = spareBits(part.length());
    List<String> out = new ArrayList<>();
    if (part.isEmpty() || spare <= 0) {
      return out;
    }
    int last = ALPHABET.indexOf(part.charAt(part.length() - 1));
    int mask = (1 << spare) - 1;
    if (last < 0 || (last & mask) != 0) {
      throw new IllegalArgumentException("not canonical: " + part);
    }
    for (int low = 1; low <= mask; low++) {
      out.add(part.substring(0, part.length() - 1) + ALPHABET.charAt(last | low));
    }
    return out;
  }

  /**
   * Every other spelling of {@code value} that the contract names, around its part {@code target}
   * (the signature, the MAC or the sealed bytes): each other last character of that part, the part
   * padded with one and with two {@code =}, the part in the standard alphabet (when it has a
   * {@code -} or {@code _}), a line feed, a carriage return and a CRLF inside it; each other part
   * with its padding; and the whole value with whitespace or a line break before or after.
   *
   * @param value the value as issued: canonical base64url parts separated by dots
   * @param target the index of the part re-spelled every way
   * @return the cases, named
   */
  public static List<Spelling> of(String value, int target) {
    String[] parts = value.split("\\.", -1);
    String part = parts[target];
    byte[] bytes = Base64Url.decode(part);
    if (bytes == null) {
      throw new IllegalArgumentException("the issued value is not canonical: " + value);
    }
    String label = parts.length == 1 ? "the value" : "part " + target;
    List<Spelling> out = new ArrayList<>();
    for (String other : lastCharacterSpellings(part)) {
      byte[] lenient = Base64.getUrlDecoder().decode(other);
      if (!Arrays.equals(lenient, bytes) || Base64Url.isCanonical(other)) {
        throw new IllegalStateException("test bug: " + other + " is not another spelling of " + part);
      }
      out.add(new Spelling(label + " ends in " + other.charAt(other.length() - 1), with(parts, target, other)));
    }
    out.add(new Spelling(label + " padded with =", with(parts, target, part + "=")));
    out.add(new Spelling(label + " padded with ==", with(parts, target, part + "==")));
    String standard = part.replace('-', '+').replace('_', '/');
    if (!standard.equals(part)) {
      out.add(new Spelling(label + " in the standard alphabet", with(parts, target, standard)));
    }
    int mid = part.length() / 2;
    out.add(new Spelling(label + " with a line feed inside", with(parts, target, part.substring(0, mid) + "\n" + part.substring(mid))));
    out.add(new Spelling(label + " with a carriage return inside", with(parts, target, part.substring(0, mid) + "\r" + part.substring(mid))));
    out.add(new Spelling(label + " with CRLF inside", with(parts, target, part.substring(0, mid) + "\r\n" + part.substring(mid))));
    for (int i = 0; i < parts.length; i++) {
      int pad = (4 - parts[i].length() % 4) % 4;
      if (i != target && pad > 0 && Base64Url.isCanonical(parts[i])) {
        out.add(new Spelling("part " + i + " padded", with(parts, i, parts[i] + "=".repeat(pad))));
      }
    }
    out.add(new Spelling("a space before", " " + value));
    out.add(new Spelling("a space after", value + " "));
    out.add(new Spelling("a tab after", value + "\t"));
    out.add(new Spelling("a line feed after", value + "\n"));
    out.add(new Spelling("CRLF after", value + "\r\n"));
    out.add(new Spelling("CRLF before", "\r\n" + value));
    for (Spelling s : out) {
      if (s.value().equals(value)) {
        throw new IllegalStateException("test bug: " + s.name() + " equals the issued value");
      }
    }
    return out;
  }

  /**
   * Changes that certainly alter the bytes of part {@code target} or the value's shape: its first
   * bit flipped, its last bit flipped, one byte shorter (re-encoded canonically), the value one
   * character shorter, the part emptied and the part removed. (A constant written over the last
   * characters is not such a change: the last character has spare bits.)
   *
   * @param value the value as issued
   * @param target the index of the part to change
   * @return the cases, named
   */
  public static List<Spelling> tampered(String value, int target) {
    String[] parts = value.split("\\.", -1);
    byte[] bytes = Base64Url.decode(parts[target]);
    if (bytes == null || bytes.length < 2) {
      throw new IllegalArgumentException("the part must be canonical and at least two bytes: " + value);
    }
    String label = parts.length == 1 ? "the value" : "part " + target;
    byte[] first = bytes.clone();
    first[0] ^= (byte) 0x80;
    byte[] last = bytes.clone();
    last[last.length - 1] ^= 0x01;
    List<Spelling> out = new ArrayList<>();
    out.add(new Spelling(label + ": first bit flipped", with(parts, target, Base64Url.encode(first))));
    out.add(new Spelling(label + ": last bit flipped", with(parts, target, Base64Url.encode(last))));
    out.add(new Spelling(label + ": one byte shorter", with(parts, target, Base64Url.encode(Arrays.copyOf(bytes, bytes.length - 1)))));
    out.add(new Spelling("one character shorter", value.substring(0, value.length() - 1)));
    out.add(new Spelling(label + " emptied", with(parts, target, "")));
    if (parts.length > 1) {
      List<String> fewer = new ArrayList<>(Arrays.asList(parts));
      fewer.remove(target);
      out.add(new Spelling(label + " removed", String.join(".", fewer)));
    }
    return out;
  }

  /**
   * Re-spellings of the parts a signature covers (a header, a payload), each signed again so that
   * the signature is valid over the new text: a verifier that decodes leniently would accept every
   * one, and only the spelling rule refuses it. For each part before the signature: each other last
   * character, the correct {@code =} padding (when the part has spare bits), and a line feed, a
   * carriage return and a CRLF inside it.
   *
   * @param value the value as issued: canonical base64url parts separated by dots, the signature (or
   *     MAC) last
   * @param sign from the text the signature covers (the parts before it joined with dots) to the
   *     signature part, which must be canonical
   * @return the cases, named
   */
  public static List<Spelling> resigned(String value, UnaryOperator<String> sign) {
    String[] parts = value.split("\\.", -1);
    List<Spelling> out = new ArrayList<>();
    for (int target = 0; target < parts.length - 1; target++) {
      String part = parts[target];
      String label = "part " + target;
      List<Spelling> variants = new ArrayList<>();
      for (String other : lastCharacterSpellings(part)) {
        variants.add(new Spelling(label + " ends in " + other.charAt(other.length() - 1), other));
      }
      int pad = (4 - part.length() % 4) % 4;
      if (pad > 0) {
        variants.add(new Spelling(label + " padded with " + "=".repeat(pad), part + "=".repeat(pad)));
      }
      int mid = part.length() / 2;
      variants.add(new Spelling(label + " with a line feed inside", part.substring(0, mid) + "\n" + part.substring(mid)));
      variants.add(new Spelling(label + " with a carriage return inside", part.substring(0, mid) + "\r" + part.substring(mid)));
      variants.add(new Spelling(label + " with CRLF inside", part.substring(0, mid) + "\r\n" + part.substring(mid)));
      for (Spelling v : variants) {
        String[] changed = parts.clone();
        changed[target] = v.value();
        changed[parts.length - 1] = sign.apply(String.join(".", java.util.Arrays.copyOfRange(changed, 0, parts.length - 1)));
        out.add(new Spelling(v.name() + ", signed again", String.join(".", changed)));
      }
    }
    return out;
  }

  private static String with(String[] parts, int index, String replacement) {
    String[] copy = parts.clone();
    copy[index] = replacement;
    return String.join(".", copy);
  }
}
