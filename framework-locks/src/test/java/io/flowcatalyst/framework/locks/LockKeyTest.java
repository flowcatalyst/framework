package io.flowcatalyst.framework.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.flowcatalyst.framework.context.ClientId;
import org.junit.jupiter.api.Test;

class LockKeyTest {

  @Test
  void storedForms() {
    assertThat(LockKey.service("nightly-export").stored()).isEqualTo("svc:nightly-export");
    assertThat(LockKey.client(new ClientId("clt_1"), "invoice-run").stored())
        .isEqualTo("client:clt_1:invoice-run");
  }

  @Test
  void aClientKeyNeverEqualsAServiceKeyInStoredForm() {
    // A service key named "client:x:y" is stored as "svc:client:x:y", never "client:x:y".
    assertThat(LockKey.service("client:x:y").stored()).isNotEqualTo(LockKey.client(new ClientId("x"), "y").stored());
    assertThat(LockKey.service("x").stored()).doesNotStartWith("client:");
  }

  @Test
  void keysOfDifferentClientsNeverCollideEvenWithColonsInTheName() {
    String a = LockKey.client(new ClientId("a"), "b:c").stored();
    // A client id with a colon is refused, so "a:b" + "c" cannot masquerade as "a" + "b:c".
    assertThatIllegalArgumentException().isThrownBy(() -> LockKey.client(new ClientId("a:b"), "c"));
    assertThat(a).isEqualTo("client:a:b:c");
  }

  @Test
  void namesAreValidated() {
    for (String bad : new String[] {"", " ", "a b", "a/b", "é", "x".repeat(201), "a\n"}) {
      assertThatIllegalArgumentException().as(bad).isThrownBy(() -> LockKey.service(bad));
    }
    for (String good : new String[] {"a", "A.b_c", "a:b", "a-b", "x".repeat(200)}) {
      LockKey.service(good);
    }
  }

  @Test
  void scopeAndClientMustAgree() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockKey(LockKey.Scope.SERVICE, new ClientId("c"), "n"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new LockKey(LockKey.Scope.CLIENT, null, "n"));
  }
}
