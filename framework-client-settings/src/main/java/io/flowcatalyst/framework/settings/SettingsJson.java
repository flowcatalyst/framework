package io.flowcatalyst.framework.settings;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The Jackson configuration of the stored values: unknown properties are ignored so a value can gain fields. */
final class SettingsJson {

  private SettingsJson() {}

  static ObjectMapper mapper() {
    return JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }
}
