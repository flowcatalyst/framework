package io.flowcatalyst.framework.ui;

import io.flowcatalyst.framework.core.App;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.http.Routes;

/**
 * Serves the module's static files ({@link UiAssets}) at {@code /fw-ui/}. Add it to an application
 * that renders pages with {@link Views}; the pages reference the files by {@link UiAssets#url}.
 */
public final class UiModule implements Module {

  /** The module id. */
  public static final String ID = "ui";

  private final UiAssets assets;

  /**
   * A module serving the given assets.
   *
   * @param assets the assets, shared with the {@link Views} that reference them
   */
  public UiModule(UiAssets assets) {
    this.assets = assets;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void boot(App app) {
    assets.register(app.get(Routes.class).forModule(ID));
  }
}
