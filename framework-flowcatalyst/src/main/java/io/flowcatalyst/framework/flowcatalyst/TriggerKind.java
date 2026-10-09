package io.flowcatalyst.framework.flowcatalyst;

/** Why the platform fired a scheduled job. */
public enum TriggerKind {
  /** Its cron schedule came due. */
  CRON,
  /** Someone fired it by hand. */
  MANUAL
}
