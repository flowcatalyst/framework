package io.flowcatalyst.framework.flowcatalyst;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What happened to one category of definitions during a sync.
 *
 * @param category the category
 * @param outcome how it ended
 * @param sent how many definitions were (or, for a dry run, would be) sent
 * @param created how many the platform created; 0 unless {@code SYNCED}
 * @param updated how many the platform updated; 0 unless {@code SYNCED}
 * @param deleted how many the platform deleted; 0 unless {@code SYNCED}
 * @param codes the codes the platform confirmed, or for a dry run the codes that would be sent
 * @param message the reason, for {@code FAILED} and for {@code SKIPPED} when something was left
 *     out on purpose
 */
public record CategoryResult(
    DefinitionSync.Category category,
    Outcome outcome,
    int sent,
    int created,
    int updated,
    int deleted,
    List<String> codes,
    @Nullable String message) {

  /** How a category ended. */
  public enum Outcome {
    /** The platform accepted the category. */
    SYNCED,
    /** Dry run: this would have been sent. */
    WOULD_SYNC,
    /** Nothing to send, or deliberately not sent (see the message). */
    SKIPPED,
    /** The attempt failed (see the message). */
    FAILED
  }

  /** Copies the code list. */
  public CategoryResult {
    codes = List.copyOf(codes);
  }

  /**
   * One line for logs and the {@code sync} command.
   *
   * @return for example {@code ROLES SYNCED sent=2 created=1 updated=1 deleted=0}
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%-15s %-10s", category, outcome));
    switch (outcome) {
      case SYNCED ->
          sb.append(" sent=").append(sent).append(" created=").append(created)
              .append(" updated=").append(updated).append(" deleted=").append(deleted);
      case WOULD_SYNC -> sb.append(" would send ").append(sent).append(": ").append(String.join(", ", codes));
      case SKIPPED, FAILED -> {}
    }
    if (message != null) {
      sb.append(" - ").append(message);
    }
    return sb.toString().stripTrailing();
  }
}
