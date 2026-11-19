package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The canonical base64url rule (token-spelling contract, section 2). */
class Base64UrlTest {

  /** What the rule means, written independently of it: the standard encoder gives the text back. */
  private static boolean reencodes(String text) {
    byte[] lenient;
    try {
      lenient = Base64.getUrlDecoder().decode(text);
    } catch (IllegalArgumentException e) {
      return false;
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(lenient).equals(text);
  }

  @Test
  void everyStringOfUpToThreeCharactersIsCanonicalExactlyWhenItReencodesToItself() {
    // every character of the alphabet plus the ones a lenient decoder might take, at every position
    String chars = Respellings.ALPHABET + "=+/ \t\r\n.";
    int k = chars.length();
    int checked = 0;
    for (int len = 0; len <= 3; len++) {
      int total = (int) Math.pow(k, len);
      for (int n = 0; n < total; n++) {
        StringBuilder s = new StringBuilder();
        for (int i = 0, m = n; i < len; i++, m /= k) {
          s.append(chars.charAt(m % k));
        }
        String text = s.toString();
        assertThat(Base64Url.isCanonical(text)).as("%s", text).isEqualTo(reencodes(text));
        byte[] decoded = Base64Url.decode(text);
        assertThat(decoded != null).as("%s", text).isEqualTo(reencodes(text));
        checked++;
      }
    }
    assertThat(checked).isEqualTo(1 + k + k * k + k * k * k);
  }

  @Test
  void encodedBytesAreCanonicalAndDecodeToThemselves() {
    Random random = new Random(20261119);
    for (int len = 0; len <= 600; len++) {
      byte[] b = new byte[len];
      random.nextBytes(b);
      String text = Base64Url.encode(b);
      assertThat(Base64Url.isCanonical(text)).as("%d bytes", len).isTrue();
      assertThat(Base64Url.decode(text)).isEqualTo(b);
      assertThat(text).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(b));
    }
  }

  @Test
  void theSpellingsTheJdkDecoderAcceptsAreRefused() {
    // 32 bytes, as an HMAC-SHA256 or a field nonce MAC: 43 characters, two spare bits
    byte[] mac = new byte[32];
    new Random(7).nextBytes(mac);
    String canonical = Base64Url.encode(mac);
    for (Respellings.Spelling s : Respellings.of(canonical, 0)) {
      assertThat(Base64Url.decode(s.value())).as(s.name()).isNull();
    }
    assertThat(Respellings.lastCharacterSpellings(canonical)).hasSize(3);
    // the JDK's own URL decoder accepts the other last characters and the padding
    assertThat(Base64.getUrlDecoder().decode(Respellings.lastCharacterSpellings(canonical).getFirst())).isEqualTo(mac);
    assertThat(Base64.getUrlDecoder().decode(canonical + "=")).isEqualTo(mac);
    assertThat(Base64Url.decode(canonical)).isEqualTo(mac);
    assertThat(Base64Url.decode(null)).isNull();
  }

  @Test
  void theLastCharacterTable() {
    // contract section 2: the characters a canonical string may end in, by length mod 4
    StringBuilder twoMod4 = new StringBuilder();
    StringBuilder threeMod4 = new StringBuilder();
    for (char c : Respellings.ALPHABET.toCharArray()) {
      if (Base64Url.isCanonical("A" + c)) {
        twoMod4.append(c);
      }
      if (Base64Url.isCanonical("AA" + c)) {
        threeMod4.append(c);
      }
    }
    assertThat(twoMod4).hasToString("AQgw");
    assertThat(threeMod4).hasToString("AEIMQUYcgkosw048");
  }
}
