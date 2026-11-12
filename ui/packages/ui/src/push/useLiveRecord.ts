// One record that stays current over push. A push on the record's topic is a signal carrying the
// record's version, not its content: when the signal's version is higher than the held one, the
// record is read again. At most one read runs at a time and at most one more is queued for a burst of
// signals; a `reset` reads again. The stream is opened FIRST and the first read follows (contract
// 9.1 order: `load` returns no cursor, so the subscription must exist before the snapshot).
import { computed, getCurrentScope, onScopeDispose, shallowRef, toValue, watch } from "vue";
import type { ComputedRef, MaybeRefOrGetter, ShallowRef } from "vue";
import type { PushMessage } from "@flowcatalyst/push";
import { usePushHub, whenMounted } from "./hub.ts";
import type { PushHub, TopicClaim } from "./hub.ts";
import type { LiveStatus } from "./useLiveList.ts";
import { versionGreater } from "./version.ts";
import type { Version } from "./version.ts";

export interface LiveRecordOptions<T> {
  /** The record's topics; a ref or getter is watched, and a change reads again. */
  readonly topics: MaybeRefOrGetter<readonly string[]>;
  /** Reads the record. `signal` aborts when a reset or a change of topics supersedes the read. */
  readonly load: (signal: AbortSignal) => Promise<T>;
  readonly version: (record: T) => Version;
  /** The version a push signals for this record, or null when the push is not about it. */
  readonly signal: (push: PushMessage) => Version | null;
  /** Default: the hub provided with `providePushHub`. */
  readonly hub?: PushHub;
}

export interface LiveRecord<T> {
  /** The record as last read; null before the first read. */
  readonly record: Readonly<ShallowRef<T | null>>;
  readonly status: ComputedRef<LiveStatus>;
  /** When (ms since the epoch) a read last finished. */
  readonly lastAppliedAt: Readonly<ShallowRef<number | null>>;
  /** The latest read failure, null after a read succeeds. The record stays as it was. */
  readonly error: Readonly<ShallowRef<unknown>>;
  /** Reads again now. */
  reload(): Promise<void>;
  /** Stops following. Called at scope end; call it yourself outside a component. */
  stop(): void;
}

export function useLiveRecord<T>(options: LiveRecordOptions<T>): LiveRecord<T> {
  const hub = options.hub ?? usePushHub();
  const record: ShallowRef<T | null> = shallowRef<T | null>(null);
  const loading = shallowRef(true);
  const stopped = shallowRef(false);
  const lastAppliedAt = shallowRef<number | null>(null);
  const error = shallowRef<unknown>(null);

  let generation = 0;
  let controller: AbortController | null = null;
  let claim: TopicClaim | null = null;
  let unlisten: (() => void)[] = [];
  let reading = false;
  let wanted: Version | null = null;
  let again = false;
  let ready = false;

  const status = computed<LiveStatus>(() => {
    if (stopped.value) {
      return "stopped";
    }
    if (loading.value) {
      return "loading";
    }
    const s = hub.state.value;
    if (s === "open") {
      return "live";
    }
    return s === "stopped" ? "stopped" : "reconnecting";
  });

  function stale(version: Version): boolean {
    const held = record.value;
    return held !== null && !versionGreater(version, options.version(held));
  }

  /** Reads until nothing newer is wanted. One loop at a time per generation; a burst queues one more read. */
  async function read(mine: number): Promise<void> {
    if (reading) {
      again = true;
      return;
    }
    reading = true;
    try {
      do {
        again = false;
        const ctl = new AbortController();
        controller = ctl;
        let failed = false;
        try {
          const result = await options.load(ctl.signal);
          if (generation !== mine) {
            return;
          }
          record.value = result;
          error.value = null;
          lastAppliedAt.value = Date.now();
        } catch (cause) {
          if (generation !== mine) {
            return;
          }
          error.value = cause;
          failed = true;
        }
        loading.value = false;
        // A failed read waits for the next signal; it does not retry in a loop.
        const held = record.value;
        const newer = !failed && wanted !== null && held !== null && versionGreater(wanted, options.version(held));
        if (!newer) {
          wanted = null;
        }
        again = newer;
      } while (again && generation === mine);
    } finally {
      if (generation === mine) {
        reading = false;
      }
    }
  }

  function onPush(push: PushMessage): void {
    let version: Version | null;
    try {
      version = options.signal(push);
    } catch (cause) {
      error.value = cause;
      return;
    }
    if (version === null || stale(version)) {
      return;
    }
    if (wanted === null || versionGreater(version, wanted)) {
      wanted = version;
    }
    // Before the first read has started, the signal only raises `wanted`: that read follows.
    if (ready) {
      void read(generation);
    }
  }

  /** Starts (or restarts) from scratch: abort what runs, subscribe, read. */
  async function start(fresh: boolean): Promise<void> {
    generation += 1;
    const mine = generation;
    controller?.abort();
    reading = false;
    again = false;
    wanted = null;
    ready = false;
    loading.value = record.value === null || !fresh;
    if (fresh) {
      for (const off of unlisten) {
        off();
      }
      const topics = [...toValue(options.topics)];
      unlisten = [...topics.map((t) => hub.on(t, onPush)), hub.onReset(() => void start(false))];
      const previous = claim;
      claim = topics.length > 0 ? hub.acquire(topics) : null;
      previous?.release();
      if (claim !== null) {
        await claim.applied;
        await hub.subscribed();
      }
    }
    if (generation !== mine || stopped.value) {
      return;
    }
    ready = true;
    await read(mine);
  }

  function stop(): void {
    stopped.value = true;
    generation += 1;
    controller?.abort();
    claim?.release();
    claim = null;
    for (const off of unlisten) {
      off();
    }
    unlisten = [];
  }

  whenMounted(() => {
    if (stopped.value) {
      return;
    }
    void start(true);
    watch(
      () => JSON.stringify(toValue(options.topics)),
      () => {
        record.value = null;
        void start(true);
      },
    );
  });
  if (getCurrentScope() !== undefined) {
    onScopeDispose(stop);
  }

  return { record, status, lastAppliedAt, error, reload: () => start(false), stop };
}
