package io.flowcatalyst.framework.settings;

import io.flowcatalyst.framework.context.ClientId;
import io.flowcatalyst.framework.usecase.Aggregate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * All of one client's setting values: the aggregate behind the {@code fw_client_settings} row
 * (contract section 3). Changes return a new value; a use case saves it with {@code Plan.save}.
 *
 * @param id the id (the client's code)
 * @param client the client
 * @param version {@code 0} until stored
 * @param values the stored values by key, as JSON
 */
public record ClientSettings(ClientSettingsId id, ClientId client, long version, Map<String, JsonNode> values)
    implements Aggregate {

  private static final ObjectMapper JSON = SettingsJson.mapper();

  /** Copies the map so the aggregate is immutable. */
  public ClientSettings {
    values = Map.copyOf(values);
  }

  /**
   * A client with no stored values, at version {@code 0}.
   *
   * @param client the client
   * @return the empty settings
   */
  public static ClientSettings empty(ClientId client) {
    return new ClientSettings(ClientSettingsId.of(client), client, 0, Map.of());
  }

  /**
   * The value of a setting, or its default.
   *
   * @param <T> the value type
   * @param setting the definition
   * @return the stored value, else the default
   * @throws IllegalStateException when the stored value does not decode as the setting's type
   */
  public <T> T get(ClientSetting<T> setting) {
    JsonNode stored = values.get(setting.key());
    if (stored == null) {
      return setting.defaultValue();
    }
    try {
      return JSON.treeToValue(stored, setting.type());
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "the stored value of client setting " + setting.key() + " for client " + client + " does not decode as "
              + setting.type().getSimpleName(),
          e);
    }
  }

  /**
   * Whether the client has a value of its own for the setting.
   *
   * @param setting the definition
   * @return true when a value is stored
   */
  public boolean isSet(ClientSetting<?> setting) {
    return values.containsKey(setting.key());
  }

  /**
   * The settings with a value for one setting.
   *
   * @param <T> the value type
   * @param setting the definition
   * @param value the new value
   * @return the new settings
   * @throws IllegalArgumentException when the setting's rule refuses the value (a use case checks
   *     with {@link ClientSetting#check} first, so a person gets a validation error)
   */
  public <T> ClientSettings with(ClientSetting<T> setting, T value) {
    Optional<String> bad = setting.check(value);
    if (bad.isPresent()) {
      throw new IllegalArgumentException("client setting " + setting.key() + ": " + bad.get());
    }
    Map<String, JsonNode> next = new LinkedHashMap<>(values);
    next.put(setting.key(), JSON.valueToTree(value));
    return new ClientSettings(id, client, version, next);
  }

  /**
   * The settings without a value for one setting, so its default applies again.
   *
   * @param setting the definition
   * @return the new settings
   */
  public ClientSettings without(ClientSetting<?> setting) {
    Map<String, JsonNode> next = new LinkedHashMap<>(values);
    next.remove(setting.key());
    return new ClientSettings(id, client, version, next);
  }
}
