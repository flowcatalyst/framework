package io.flowcatalyst.framework.http.openapi;

import io.flowcatalyst.framework.http.Operation;
import java.util.List;

/** What modules contribute to the document in {@code register}, through {@link OpenApi}. */
sealed interface Contribution {

  /** The module that contributed it. */
  String module();

  record Operations(String module, List<Operation<?, ?>> operations) implements Contribution {}

  record Info(String module, String title, String version) implements Contribution {}

  record TypeSchema(String module, Class<?> type, Schema schema) implements Contribution {}

  record TypeName(String module, Class<?> type, String name) implements Contribution {}

  record PropertySchema(String module, Class<?> type, String property, Schema schema) implements Contribution {}

  record Security(String module, String name, SecurityScheme scheme) implements Contribution {}
}
