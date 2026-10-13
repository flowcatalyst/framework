package io.flowcatalyst.framework.auth;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** The JSON mapper of this module: trees only, duplicate member names refused. */
final class Records {

  static final JsonMapper MAPPER = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private Records() {}
}
