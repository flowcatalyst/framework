package io.flowcatalyst.framework.push;

import io.flowcatalyst.framework.metrics.Counter;
import io.flowcatalyst.framework.metrics.Gauge;
import io.flowcatalyst.framework.metrics.Metrics;
import io.flowcatalyst.framework.metrics.Timer;

/** The metrics of push contract section 11, named as the Go framework names them. */
final class PushMetrics {

  final Counter published;
  final Timer relayLag;
  final Gauge logId;
  final Gauge open;
  final Counter delivered;
  final Counter ended;
  final Counter resets;
  final Counter replaySource;

  PushMetrics(Metrics metrics) {
    published = metrics.counter("fw_push_published_total", "Pushes logged by the relay (each committed push once).");
    relayLag = metrics.timer("fw_push_relay_lag_seconds", "Age of the oldest outbox row a relay run moved.");
    logId = metrics.gauge("fw_push_log_id", "Highest push log id this instance has followed.");
    open = metrics.gauge("fw_push_streams_open", "Push streams open on this instance.");
    delivered = metrics.counter("fw_push_delivered_total", "Pushes written to streams (live and replayed).");
    ended = metrics.counter("fw_push_streams_ended_total", "Push streams ended, by reason.", "reason");
    resets = metrics.counter("fw_push_resets_total", "Reset events sent, by reason.", "reason");
    replaySource = metrics.counter("fw_push_replay_source_total", "Replays served, by source (memory or log).", "source");
  }
}
