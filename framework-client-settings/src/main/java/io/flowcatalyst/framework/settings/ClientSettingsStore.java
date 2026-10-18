package io.flowcatalyst.framework.settings;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Reads a client's setting values (contract section 4). No cache: every call reads the database.
 */
public final class ClientSettingsStore {

  private final Database database;

  /**
   * Creates the store.
   *
   * @param database the database holding {@code fw_client_settings}
   */
  public ClientSettingsStore(Database database) {
    this.database = database;
  }

  /**
   * The client's value of a setting, or the setting's default when the client has none.
   *
   * @param <T> the value type
   * @param client the client
   * @param setting the definition
   * @return the value
   * @throws SQLException on a database error
   * @throws IllegalStateException when the stored value does not decode as the setting's type
   */
  public <T> T get(ClientId client, ClientSetting<T> setting) throws SQLException {
    try (Connection c = database.dataSource().getConnection()) {
      return ClientSettingsRepository.read(c, client).map(s -> s.get(setting)).orElseGet(setting::defaultValue);
    }
  }
}
