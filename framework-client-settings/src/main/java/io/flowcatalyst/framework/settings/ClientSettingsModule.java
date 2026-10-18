package io.flowcatalyst.framework.settings;

import io.flowcatalyst.framework.core.AppBuilder;
import io.flowcatalyst.framework.core.Module;
import io.flowcatalyst.framework.database.Databases;
import io.flowcatalyst.framework.database.MigrationSet;

/**
 * Contributes the {@code fw_client_settings} migration and provides {@link ClientSettingsStore} and
 * {@link ClientSettingsRepository}. List it after the database module.
 */
public final class ClientSettingsModule implements Module {

  /** The module id; the migration history is keyed by it. */
  public static final String ID = "client-settings";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void register(AppBuilder app) {
    MigrationSet.contribute(app, ClientSettingsModule.class, "db/migration/client-settings");
    app.provide(ClientSettingsRepository.class, lookup -> new ClientSettingsRepository());
    app.provide(ClientSettingsStore.class, lookup -> new ClientSettingsStore(lookup.get(Databases.class).defaultInstance()));
  }
}
