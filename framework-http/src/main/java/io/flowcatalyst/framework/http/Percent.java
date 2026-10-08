package io.flowcatalyst.framework.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Percent-decoding of path segments and query components. */
final class Percent {

  private Percent() {}

  /**
   * Decodes UTF-8 percent-escapes.
   *
   * @param s the encoded text
   * @param plusIsSpace true for query strings (form encoding), false for paths
   * @return the decoded text
   * @throws ValidationException on a malformed escape or invalid UTF-8
   */
  static String decode(String s, boolean plusIsSpace) {
    if (s.indexOf('%') < 0 && (!plusIsSpace || s.indexOf('+') < 0)) {
      return s;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '%') {
        if (i + 2 >= s.length()) {
          throw bad();
        }
        int hi = Character.digit(s.charAt(i + 1), 16);
        int lo = Character.digit(s.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) {
          throw bad();
        }
        out.write((hi << 4) | lo);
        i += 2;
      } else if (c == '+' && plusIsSpace) {
        out.write(' ');
      } else {
        byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
        if (Character.isHighSurrogate(c) && i + 1 < s.length()) {
          bytes = s.substring(i, i + 2).getBytes(StandardCharsets.UTF_8);
          i++;
        }
        out.writeBytes(bytes);
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(out.toByteArray()))
          .toString();
    } catch (CharacterCodingException e) {
      throw bad();
    }
  }

  private static ValidationException bad() {
    return ValidationException.of("path", "INVALID_ENCODING", "contains an invalid percent-escape");
  }
}
