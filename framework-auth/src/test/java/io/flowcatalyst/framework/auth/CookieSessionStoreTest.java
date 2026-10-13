package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.config.Secret;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Contract section 6.5: the sealed cookie format, keys and rotation. */
class CookieSessionStoreTest {

  final MutableClock clock = new MutableClock(Instant.parse("2026-10-13T08:00:00Z"));

  private CookieSessionStore store(String keys) {
    return CookieSessionStore.create(CookieKeys.parse(Secret.of(keys)), clock);
  }

  private Instant in(Duration d) {
    return clock.instant().plus(d);
  }

  @Test
  void aValueSealedWithAnotherKeyIsNone() {
    String sealed = store(CookieKeys.generate("k1")).create(SessionStore.SESSION, "{\"a\":1}", in(Duration.ofMinutes(5)));
    assertThat(store(CookieKeys.generate("k1")).read(SessionStore.SESSION, sealed)).isEmpty();
  }

  @Test
  void aChangedValueIsNone() {
    CookieSessionStore s = store(CookieKeys.generate("k1"));
    String sealed = s.create(SessionStore.SESSION, "{\"a\":1}", in(Duration.ofMinutes(5)));
    String[] parts = sealed.split("\\.");
    byte[] raw = Base64.getUrlDecoder().decode(parts[2]);
    for (int i : new int[] {0, 11, 12, raw.length / 2, raw.length - 1}) {
      byte[] copy = raw.clone();
      copy[i] ^= 1;
      String changed = parts[0] + "." + parts[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(copy);
      assertThat(s.read(SessionStore.SESSION, changed)).as("byte %d", i).isEmpty();
    }
    assertThat(s.read(SessionStore.SESSION, "v2." + parts[1] + "." + parts[2])).isEmpty();
    assertThat(s.read(SessionStore.SESSION, parts[0] + ".k9." + parts[2])).isEmpty();
    assertThat(s.read(SessionStore.SESSION, parts[0] + "." + parts[1] + ".AAAA")).isEmpty();
    assertThat(s.read(SessionStore.SESSION, "garbage")).isEmpty();
    assertThat(s.read(SessionStore.SESSION, sealed)).isPresent();
  }

  @Test
  void aLoginRecordCannotBeUsedAsASession() {
    CookieSessionStore s = store(CookieKeys.generate("k1"));
    String login = s.create(SessionStore.LOGIN, "{\"a\":1}", in(Duration.ofMinutes(5)));
    assertThat(s.read(SessionStore.SESSION, login)).isEmpty();
    assertThat(s.read(SessionStore.LOGIN, login)).isPresent();
  }

  @Test
  void anExpiredValueIsNone() {
    CookieSessionStore s = store(CookieKeys.generate("k1"));
    String sealed = s.create(SessionStore.SESSION, "{}", in(Duration.ofMinutes(5)));
    clock.advance(Duration.ofMinutes(5));
    assertThat(s.read(SessionStore.SESSION, sealed)).isEmpty();
  }

  @Test
  void keysRotateTheFirstSealsAndAllOpen() {
    String old = CookieKeys.generate("old");
    String fresh = CookieKeys.generate("new");
    String sealedByOld = store(old).create(SessionStore.SESSION, "{\"a\":1}", in(Duration.ofMinutes(5)));
    CookieSessionStore rotated = store(fresh + "," + old);
    assertThat(rotated.read(SessionStore.SESSION, sealedByOld)).isPresent();
    assertThat(rotated.create(SessionStore.SESSION, "{}", in(Duration.ofMinutes(5)))).startsWith("v1.new.");
    assertThat(store(fresh).read(SessionStore.SESSION, sealedByOld)).isEmpty();
  }

  @Test
  void theStoreIsNotRevocableAndUpdateReseals() {
    CookieSessionStore s = store(CookieKeys.generate("k1"));
    String sealed = s.create(SessionStore.SESSION, "{\"n\":1}", in(Duration.ofMinutes(5)));
    String next = s.update(SessionStore.SESSION, sealed, "{\"n\":2}", in(Duration.ofMinutes(5)));
    assertThat(next).isNotEqualTo(sealed);
    assertThat(s.read(SessionStore.SESSION, next)).contains("{\"n\":2}");
    s.delete(SessionStore.SESSION, next);
    assertThat(s.read(SessionStore.SESSION, next)).isPresent();
    assertThat(s.revocable()).isFalse();
  }

  @Test
  void aRecordTooLargeForACookieFails() {
    CookieSessionStore s = store(CookieKeys.generate("k1"));
    String big = "{\"roles\":[\"" + "r".repeat(3_000) + "\"]}";
    Throwable e = catchThrowable(() -> s.create(SessionStore.SESSION, big, in(Duration.ofMinutes(5))));
    assertThat(e).isInstanceOf(SessionStoreException.class).hasMessageContaining("database or redis");
  }

  @Test
  void badKeyListsFailWithoutShowingKeyMaterial() {
    String key = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    String shortKey = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    assertThatIllegalArgumentException().isThrownBy(() -> CookieKeys.parse(Secret.of(""))).withMessageNotContaining(key);
    assertThatIllegalArgumentException().isThrownBy(() -> CookieKeys.parse(Secret.of(key))).withMessageNotContaining(key);
    assertThatIllegalArgumentException().isThrownBy(() -> CookieKeys.parse(Secret.of("k1:" + shortKey))).withMessageContaining("32 bytes").withMessageNotContaining(shortKey);
    assertThatIllegalArgumentException().isThrownBy(() -> CookieKeys.parse(Secret.of("k1:" + key + ",k1:" + key))).withMessageContaining("twice");
    assertThatIllegalArgumentException().isThrownBy(() -> CookieKeys.parse(Secret.of("bad kid:" + key))).withMessageNotContaining(key);
    assertThatIllegalArgumentException().isThrownBy(() -> CookieKeys.parse(Secret.of("k1:not base64!"))).withMessageNotContaining("not base64!");
    assertThat(CookieKeys.parse(Secret.of(" a:" + key + " , b:" + key)).kids()).containsExactly("a", "b");
    assertThat(CookieKeys.parse(Secret.of("a:" + key)).toString()).doesNotContain(key);
  }
}
