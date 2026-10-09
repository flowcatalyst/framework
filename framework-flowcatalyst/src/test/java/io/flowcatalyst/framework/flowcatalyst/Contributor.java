package io.flowcatalyst.framework.flowcatalyst;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A module that contributes definitions, plus helpers to start an app around the fake platform. */
final class Contributor implements Module {

  private final String id;
  private final Consumer<Definitions> contribute;

  private Contributor(String id, Consumer<Definitions> contribute) {
    this.id = id;
    this.contribute = contribute;
  }

  static Contributor of(String id, Consumer<Definitions> contribute) {
    return new Contributor(id, contribute);
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public void register(AppBuilder app) {
    contribute.accept(Definitions.in(app));
  }

  /** Environment pointing the module at a fake platform, as application {@code orders}. */
  static Map<String, String> env(FakePlatform platform) {
    Map<String, String> env = new HashMap<>();
    env.put("FLOWCATALYST_BASE_URL", platform.baseUrl());
    env.put("FLOWCATALYST_APPLICATION", "orders");
    env.put("FLOWCATALYST_CLIENT_ID", FakePlatform.CLIENT_ID);
    env.put("FLOWCATALYST_CLIENT_SECRET", FakePlatform.CLIENT_SECRET);
    return env;
  }
}
