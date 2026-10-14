package io.flowcatalyst.framework.http.jetty;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.Routes;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Starts applications with a real server on port 0. */
final class Apps {

  private Apps() {}

  /** A module that registers routes in boot. */
  static Module routes(String id, Consumer<Routes> registrar) {
    return new Module() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public void boot(App app) {
        registrar.accept(app.get(Routes.class).forModule(id));
      }
    };
  }

  static Map<String, String> env(String... kv) {
    Map<String, String> env = new HashMap<>();
    env.put("HTTP_PORT", "0");
    env.put("HTTP_HOST", "127.0.0.1");
    for (int i = 0; i < kv.length; i += 2) {
      env.put(kv[i], kv[i + 1]);
    }
    return env;
  }

  static App start(Path dir, Map<String, String> env, Consumer<Routes> registrar, Module... more) {
    return start(dir, env, b -> b, registrar, more);
  }

  /** As above, with the builder adjusted first (behaviour in code: drain, health policy). */
  static App start(
      Path dir,
      Map<String, String> env,
      UnaryOperator<AppBuilder> adjust,
      Consumer<Routes> registrar,
      Module... more) {
    AppBuilder b = adjust.apply(App.builder().configDirectory(dir).environment(env));
    b.module(routes("test", registrar));
    for (Module m : more) {
      b.module(m);
    }
    b.module(new JettyHttpModule());
    return b.start();
  }

  static String url(App app, String path) {
    return "http://127.0.0.1:" + app.get(HttpServer.class).port() + path;
  }
}
