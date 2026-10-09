package io.flowcatalyst.framework.flowcatalyst;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of {@link DefinitionSync#run}: one result per category, in the order they were
 * attempted.
 *
 * @param mode whether anything was written
 * @param categories one result per category
 */
public record SyncReport(DefinitionSync.Mode mode, List<CategoryResult> categories) {

  /** Copies the list. */
  public SyncReport {
    categories = List.copyOf(categories);
  }

  /**
   * The result of one category.
   *
   * @param category the category
   * @return its result; every category is always present
   */
  public CategoryResult of(DefinitionSync.Category category) {
    Optional<CategoryResult> found =
        categories.stream().filter(r -> r.category() == category).findFirst();
    return found.orElseThrow();
  }

  /**
   * Whether any category failed.
   *
   * @return true when at least one result is {@code FAILED}
   */
  public boolean hasFailures() {
    return categories.stream().anyMatch(r -> r.outcome() == CategoryResult.Outcome.FAILED);
  }

  /**
   * One line per category.
   *
   * @return the lines, in attempt order
   */
  public List<String> describe() {
    return categories.stream().map(CategoryResult::describe).toList();
  }
}
