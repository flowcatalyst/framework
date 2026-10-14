package io.flowcatalyst.framework.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The auth component holds connections and secrets only (config-and-code contract, section 3.9). */
class AuthConfigTest {

  @TempDir Path dir;

  @Test
  void aKeyUnderAuthThatNoModuleDeclaresFailsStartUp() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "auth:\n  isuer: https://typo.example.com\n  idle-timeout: 1h\n");
    Throwable t = catchThrowable(() -> AuthApp.start(dir, b -> {}, Map.of()).close());
    assertThat(t).isInstanceOf(StartupException.class);
    assertThat(((StartupException) t).problems())
        .extracting(p -> p.key())
        .containsExactlyInAnyOrder("auth.isuer", "auth.idle-timeout");
    assertThat(t.getMessage()).contains("auth.issuer").contains("module 'auth'");
  }

  @Test
  void theDeclaredKeysAreAccepted() throws IOException {
    Files.writeString(dir.resolve("application.yaml"), "auth:\n  client-id: from-file\n");
    try (AuthApp app = AuthApp.start(dir, b -> {}, Map.of())) {
      assertThat(app).isNotNull();
    }
  }
}
