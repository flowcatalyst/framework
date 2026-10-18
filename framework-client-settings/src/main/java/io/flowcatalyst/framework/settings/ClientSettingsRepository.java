package io.flowcatalyst.framework.settings;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.database.Database;
import io.flowcatalyst.framework.usecase.Repository;
import io.flowcatalyst.framework.usecase.Tx;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stores {@link ClientSettings} in {@code fw_client_settings} (write-path contract, section 2). */
public final class ClientSettingsRepository implements Repository<ClientSettingsId, ClientSettings> {

  private static final ObjectMapper JSON = SettingsJson.mapper();

  @Override
  public Optional<ClientSettings> find(Database db, ClientId client, ClientSettingsId id) throws SQLException {
    if (!id.value().equals(client.value())) {
      return Optional.empty();
    }
    try (Connection c = db.dataSource().getConnection()) {
      return read(c, client);
    }
  }

  /**
   * The client's settings, or an empty aggregate at version {@code 0} when it has none stored (a
   * {@code Plan.save} then inserts it).
   *
   * @param db the database
   * @param client the client
   * @return the settings
   * @throws SQLException on a database error
   */
  public ClientSettings forClient(Database db, ClientId client) throws SQLException {
    return find(db, client, ClientSettingsId.of(client)).orElseGet(() -> ClientSettings.empty(client));
  }

  static Optional<ClientSettings> read(Connection c, ClientId client) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT version, settings::text FROM fw_client_settings WHERE client_id = ?")) {
      s.setString(1, client.value());
      try (ResultSet rs = s.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(new ClientSettings(ClientSettingsId.of(client), client, rs.getLong(1), parse(rs.getString(2))));
      }
    }
  }

  private static Map<String, JsonNode> parse(String json) {
    Map<String, JsonNode> values = new LinkedHashMap<>();
    JSON.readTree(json).properties().forEach(e -> values.put(e.getKey(), e.getValue()));
    return values;
  }

  private static String write(ClientSettings settings) {
    return JSON.writeValueAsString(settings.values());
  }

  @Override
  public void insert(Tx tx, ClientSettings settings) throws SQLException {
    try (PreparedStatement s =
        tx.connection().prepareStatement("INSERT INTO fw_client_settings (client_id, version, settings) VALUES (?, 1, ?::jsonb)")) {
      s.setString(1, settings.client().value());
      s.setString(2, write(settings));
      s.executeUpdate();
    }
  }

  @Override
  public int update(Tx tx, ClientSettings settings) throws SQLException {
    try (PreparedStatement s =
        tx.connection()
            .prepareStatement("UPDATE fw_client_settings SET version = ?, settings = ?::jsonb WHERE client_id = ? AND version = ?")) {
      s.setLong(1, settings.version() + 1);
      s.setString(2, write(settings));
      s.setString(3, settings.client().value());
      s.setLong(4, settings.version());
      return s.executeUpdate();
    }
  }

  @Override
  public int delete(Tx tx, ClientSettings settings) throws SQLException {
    try (PreparedStatement s = tx.connection().prepareStatement("DELETE FROM fw_client_settings WHERE client_id = ? AND version = ?")) {
      s.setString(1, settings.client().value());
      s.setLong(2, settings.version());
      return s.executeUpdate();
    }
  }
}
