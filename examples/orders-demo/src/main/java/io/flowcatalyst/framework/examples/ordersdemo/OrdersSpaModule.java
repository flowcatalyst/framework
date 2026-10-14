package io.flowcatalyst.framework.examples.ordersdemo;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.Csp;
import io.flowcatalyst.framework.http.Routes;
import io.flowcatalyst.framework.http.Spa;

/**
 * Serves the orders single-page application ({@code ui/apps/orders}) from the jar: the build's
 * output, {@code web/dist}, is copied under {@code fw-spa} by this module's pom (the static-assets
 * contract, section 8). The policy is the strict one (section 7); its only script hash is the
 * pre-paint colour-mode snippet {@code @flowcatalyst/ui} writes into {@code index.html}.
 */
public final class OrdersSpaModule implements Module {

  /** The module id. */
  public static final String ID = "orders-spa";

  /**
   * {@code COLOR_MODE_SNIPPET_SHA256} of {@code @flowcatalyst/ui/csp}, without the quotes. The
   * package's own unit test fails when the snippet changes; change this with it.
   */
  static final String COLOR_MODE_SNIPPET_HASH = "sha256-xo9261yOpFM47x0zwjr2iUF9sFaw/YoSkqnnzaDaOC8=";

  @Override
  public String id() {
    return ID;
  }

  /**
   * The SPA as served: the jar's {@code fw-spa} directory under the strict policy.
   *
   * @return the SPA
   */
  static Spa spa() {
    return Spa.classpath("fw-spa").contentSecurityPolicy(Csp.strict(COLOR_MODE_SNIPPET_HASH)).build();
  }

  @Override
  public void boot(App app) {
    app.get(Routes.class).forModule(ID).spa(spa());
  }
}
