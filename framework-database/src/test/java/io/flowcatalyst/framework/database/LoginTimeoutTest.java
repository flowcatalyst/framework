package io.flowcatalyst.framework.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.StartupException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code pool.login-timeout}: needs no Docker, a local socket plays the database that hangs. */
class LoginTimeoutTest {

  @TempDir Path dir;

  private StartupException failure(String url, String pool, Map<String, String> env) throws Exception {
    Files.writeString(
        dir.resolve("application.yaml"),
        "database:\n  instances:\n    main: { url: \"" + url + "\", username: u, password: p"
            + pool + " }\n");
    Throwable t =
        catchThrowable(
            () ->
                App.builder()
                    .configDirectory(dir)
                    .environment(env)
                    .module(new DatabaseModule())
                    .start()
                    .close());
    assertThat(t).isInstanceOf(StartupException.class);
    return (StartupException) t;
  }

  @Test
  void aServerThatAcceptsAndNeverAnswersFailsStartUpWithinTheTimeoutNamingTheInstanceAndTheSettings()
      throws Exception {
    try (ServerSocket blackHole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      String url = "jdbc:postgresql://127.0.0.1:" + blackHole.getLocalPort() + "/none";
      long start = System.nanoTime();
      StartupException e = failure(url, ", pool: { connect-timeout: 1s, login-timeout: 2s }", Map.of());
      Duration took = Duration.ofNanos(System.nanoTime() - start);

      // Bounded by the login timeout (the pool waits for the hung attempt when it closes), with room
      // for a slow machine, and far below the 10s default or "for ever".
      assertThat(took).isLessThan(Duration.ofSeconds(8));
      assertThat(e.getMessage())
          .contains("cannot connect to database instance 'main'")
          .contains("pool.connect-timeout")
          .contains("pool.login-timeout")
          .doesNotContain("password");
    }
  }

  @Test
  void theLoginTimeoutIsRangeCheckedInWholeSecondsBeforeAnythingConnects() throws Exception {
    String url = "jdbc:postgresql://127.0.0.1:1/none"; // never contacted: the check comes first
    for (String bad : new String[] {"0s", "999ms", "1500ms", "301s", "6m"}) {
      String variable = "DATABASE_INSTANCES_MAIN_POOL_LOGIN_TIMEOUT";
      StartupException e = failure(url, "", Map.of(variable, bad));
      assertThat(e.problems()).singleElement().satisfies(p -> {
        assertThat(p.key()).isEqualTo("database.instances.main.pool.login-timeout");
        assertThat(p.source()).isEqualTo("environment variable " + variable);
        assertThat(p.message()).contains("1s to 5m in whole seconds");
      });
    }
  }

  @Test
  void theDefaultIsTenSecondsAndSitsInsideTheRange() {
    assertThat(DatabaseModule.DEFAULT_LOGIN_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    assertThat(DatabaseModule.DEFAULT_LOGIN_TIMEOUT)
        .isBetween(DatabaseModule.MIN_LOGIN_TIMEOUT, DatabaseModule.MAX_LOGIN_TIMEOUT);
  }
}
