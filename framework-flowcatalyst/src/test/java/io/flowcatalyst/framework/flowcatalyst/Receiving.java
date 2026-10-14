package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.core.MonotonicClock;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.TestClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Starts an application with the FlowCatalyst module and receivers, for receiver tests. */
final class Receiving {

  /** The signing secret the tests configure. Not a real secret. */
  static final String SECRET = "whsec-test-only";

  /** The time the tests run at. */
  static final Instant NOW = Instant.parse("2026-10-09T12:00:00.000Z");

  /** {@link #NOW} as the platform formats it. */
  static final String TS = "2026-10-09T12:00:00.000Z";

  private Receiving() {}

  /** A module that provides {@link Routes}, as the HTTP module would. */
  static Module routes() {
    return new Module() {
      @Override
      public String id() {
        return "http";
      }

      @Override
      public void register(AppBuilder app) {
        app.provide(Routes.class, lookup -> new Routes());
      }
    };
  }

  /** A module that registers receivers in {@code boot}. */
  static Module handlers(Consumer<Receivers> registrar) {
    return new Module() {
      @Override
      public String id() {
        return "handlers";
      }

      @Override
      public void boot(App app) {
        registrar.accept(app.get(Receivers.class));
      }
    };
  }

  static Map<String, String> env(FakePlatform platform, String... extra) {
    Map<String, String> env = new HashMap<>();
    env.put("FLOWCATALYST_BASE_URL", platform.baseUrl());
    env.put("FLOWCATALYST_APPLICATION", "orders");
    env.put("FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID);
    env.put("FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
    env.put("FLOWCATALYST_RECEIVERS_SIGNING_SECRET", SECRET);
    for (int i = 0; i < extra.length; i += 2) {
      if (extra[i + 1] == null) {
        env.remove(extra[i]);
      } else {
        env.put(extra[i], extra[i + 1]);
      }
    }
    return env;
  }

  static App start(Path dir, Map<String, String> env, InstantSource clock, Module... modules) {
    return start(dir, env, clock, MonotonicClock.system(), modules);
  }

  static App start(
      Path dir, Map<String, String> env, InstantSource clock, MonotonicClock monotonic, Module... modules) {
    return start(dir, env, clock, monotonic, FlowCatalystOptions.defaults(), modules);
  }

  /** As above, with the module's behaviour given in code. */
  static App start(
      Path dir,
      Map<String, String> env,
      InstantSource clock,
      MonotonicClock monotonic,
      FlowCatalystOptions options,
      Module... modules) {
    AppBuilder b =
        App.builder().configDirectory(dir).environment(env).instantSource(clock).monotonicClock(monotonic);
    b.module(routes());
    for (Module m : modules) {
      b.module(m);
    }
    b.module(new FlowCatalystModule(options));
    return b.start();
  }

  /** The default options with the receivers adjusted. */
  static FlowCatalystOptions receivers(java.util.function.UnaryOperator<ReceiverOptions> adjust) {
    return FlowCatalystOptions.defaults().withReceivers(adjust.apply(ReceiverOptions.defaults()));
  }

  static TestClient client(App app) {
    return TestClient.of(app.get(Routes.class));
  }

  static String sign(String secret, String timestamp, String body) {
    return DeliverySignature.hmacHex(secret, timestamp, body.getBytes(StandardCharsets.UTF_8));
  }
}
