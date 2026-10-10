package io.flowcatalyst.framework.httpclient;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/** The default mapper: the framework's Jackson 3 style, tolerant of unknown properties on read. */
final class JsonMapping {

  private static final ObjectMapper TOLERANT =
      JsonMapper.builder()
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private JsonMapping() {}

  static ObjectMapper tolerant() {
    return TOLERANT;
  }
}
