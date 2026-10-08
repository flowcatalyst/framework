package io.flowcatalyst.framework.usecase;

import java.nio.file.Path;

/** Where the FlowCatalyst Java SDK checkout is, for the tests that compare against it. */
final class SdkFiles {

  private SdkFiles() {}

  static Path root() {
    String configured = System.getProperty("fc.sdk.dir");
    return Path.of(
        configured != null
            ? configured
            : "/Users/andrewgraaff/Developer/flowcatalyst-go/clients/java-sdk");
  }

  static Path outboxMigration() {
    return root().resolve("migrations/postgresql/001_create_outbox_messages.sql");
  }
}
