// A list that stays current over push (docs/contracts/push.md section 9.1): load the rows with the
// push cursor, listen from it, apply each pushed change by version, load again on `reset`.
//
// Ordering (no gap between the snapshot and the stream):
// - The hub is not streaming: the list loads first and the stream is opened from the load's
//   `pushCursor` (`Last-Event-ID`). The endpoint must read the cursor BEFORE its rows (contract 9.1).
// - The hub is already streaming (another list, or a change of this list's topics): the topics are
//   added FIRST, so the stream resubscribes with them and resumes from its last id (the host replays
//   every push after it on the new topic set, contract 6.2 step 2), the list waits for the stream to
//   confirm (`subscribed`), THEN loads. Pushes that arrive while loading are buffered and applied
//   after the load, by the version rule.
// A load that returns no `pushCursor` cannot be followed gap-free: a change committed between its read
// and the stream opening is missed until the next load.
import { computed, getCurrentScope, onScopeDispose, shallowRef, toValue, watch } from "vue";
import type { ComputedRef, MaybeRefOrGetter, ShallowRef } from "vue";
import type { PushMessage } from "@flowcatalyst/push";
import { usePushHub, whenMounted } from "./hub.ts";
import type { PushHub, TopicClaim } from "./hub.ts";
import { versionGreater } from "./version.ts";
import type { Version } from "./version.ts";

/** At most this many removed ids are remembered; the oldest is dropped first. */
export const REMOVED_MEMORY_LIMIT = 10_000;

export type RowId = number | string;

/** What a push (or the caller) does to the list. */
export type LiveChange<Row> =
  | { readonly kind: "put"; readonly row: Row }
  | { readonly kind: "remove"; readonly id: RowId; readonly version: Version };

export type LiveStatus = "loading" | "live" | "reconnecting" | "stopped";

export interface LiveLoad<Row> {
  readonly rows: readonly Row[];
  /** The push cursor, read BEFORE the rows (contract 9.1). */
  readonly pushCursor?: string;
}

export interface LiveListOptions<Row> {
  /** The topics to follow; a ref or getter is watched, and a change loads again. */
  readonly topics: MaybeRefOrGetter<readonly string[]>;
  /** Reads the rows and the push cursor. `signal` aborts when a newer load supersedes this one. */
  readonly load: (signal: AbortSignal) => Promise<LiveLoad<Row>>;
  readonly id: (row: Row) => RowId;
  /**
   * A row's version. A number or a string; two that both parse as numbers are compared numerically
   * (integers exactly), otherwise as strings.
   */
  readonly version: (row: Row) => Version;
  /** Turns a push into a change, or null when it is not for this list. */
  readonly toChange: (push: PushMessage) => LiveChange<Row> | null;
  /** Default: the hub provided with `providePushHub`. */
  readonly hub?: PushHub;
}

export interface LiveList<Row> {
  /** The rows, in load order; rows first seen by a push are appended. Replace, never mutate. */
  readonly rows: ComputedRef<readonly Row[]>;
  readonly status: ComputedRef<LiveStatus>;
  /** When (ms since the epoch) the list was last made current: a load finished, or a push or `apply` changed it. */
  readonly lastAppliedAt: Readonly<ShallowRef<number | null>>;
  /** The latest load failure, null after a load succeeds. The rows stay as they were. */
  readonly error: Readonly<ShallowRef<unknown>>;
  /** Applies a change by the version rule, for a screen showing the answer to its own command. True when the list changed. */
  apply(change: LiveChange<Row>): boolean;
  /** Loads again from scratch (the removed-id memory is cleared). */
  reload(): Promise<void>;
  /** Stops following. Called at scope end; call it yourself outside a component. */
  stop(): void;
}

type Payload = Readonly<Record<string, unknown>>;

function isPayload(d: unknown): d is Payload {
  return typeof d === "object" && d !== null && !Array.isArray(d);
}

function isRowId(v: unknown): v is RowId {
  return typeof v === "string" || typeof v === "number";
}

/**
 * The `toChange` for the shape `row.upserted {id, version, row}` and `row.removed {id, version}`: the
 * push's `type` is the name, its `data` the object.
 */
export function rowChange<Row>(push: PushMessage): LiveChange<Row> | null {
  const data = push.data;
  if (!isPayload(data)) {
    return null;
  }
  if (push.type === "row.upserted") {
    const row: unknown = data["row"];
    // eslint-disable-next-line @typescript-eslint/consistent-type-assertions -- the application's row type cannot be checked here; its `id` and `version` functions read it
    return row === undefined || row === null ? null : { kind: "put", row: row as Row };
  }
  if (push.type === "row.removed") {
    const id = data["id"];
    const version = data["version"];
    return isRowId(id) && isRowId(version) ? { kind: "remove", id, version } : null;
  }
  return null;
}

export function useLiveList<Row>(options: LiveListOptions<Row>): LiveList<Row> {
  const hub = options.hub ?? usePushHub();
  const held = new Map<RowId, Row>();
  const removed = new Map<RowId, Version>();
  const revision = shallowRef(0);
  const loading = shallowRef(true);
  const stopped = shallowRef(false);
  const lastAppliedAt = shallowRef<number | null>(null);
  const error = shallowRef<unknown>(null);

  let generation = 0;
  let controller: AbortController | null = null;
  let claim: TopicClaim | null = null;
  let unlisten: (() => void)[] = [];
  let buffer: LiveChange<Row>[] | null = null;
  let currentTopics: readonly string[] = [];

  const rows = computed<readonly Row[]>(() => {
    // eslint-disable-next-line @typescript-eslint/no-meaningless-void-operator -- reads the revision so the computed tracks it
    void revision.value;
    return [...held.values()];
  });

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

  function touch(): void {
    revision.value += 1;
    lastAppliedAt.value = Date.now();
  }

  function remember(id: RowId, version: Version): void {
    const before = removed.get(id);
    removed.delete(id);
    removed.set(id, before !== undefined && versionGreater(before, version) ? before : version);
    if (removed.size > REMOVED_MEMORY_LIMIT) {
      const oldest = removed.keys().next();
      if (oldest.done !== true) {
        removed.delete(oldest.value);
      }
    }
  }

  /** The version rule. True when the held rows changed. */
  function applyNow(change: LiveChange<Row>): boolean {
    if (change.kind === "put") {
      const id = options.id(change.row);
      const version = options.version(change.row);
      const gone = removed.get(id);
      if (gone !== undefined && !versionGreater(version, gone)) {
        return false;
      }
      const current = held.get(id);
      if (current !== undefined && !versionGreater(version, options.version(current))) {
        return false;
      }
      held.set(id, change.row);
      removed.delete(id);
      return true;
    }
    const current = held.get(change.id);
    if (current !== undefined && versionGreater(options.version(current), change.version)) {
      return false;
    }
    remember(change.id, change.version);
    return held.delete(change.id);
  }

  function apply(change: LiveChange<Row>): boolean {
    // During a load the change is also kept for after it: the load's rows may predate it.
    buffer?.push(change);
    const changed = applyNow(change);
    if (changed) {
      touch();
    }
    return changed;
  }

  function onPush(push: PushMessage): void {
    let change: LiveChange<Row> | null;
    try {
      change = options.toChange(push);
    } catch (cause) {
      error.value = cause;
      return;
    }
    if (change === null) {
      return;
    }
    if (buffer !== null) {
      buffer.push(change);
      return;
    }
    if (applyNow(change)) {
      touch();
    }
  }

  function finishLoad(result: LiveLoad<Row>): void {
    held.clear();
    for (const row of result.rows) {
      applyNow({ kind: "put", row });
    }
    const queued = buffer ?? [];
    buffer = null;
    for (const change of queued) {
      applyNow(change);
    }
    error.value = null;
    loading.value = false;
    touch();
  }

  /** Loads and follows. `fresh`: the topic set changed (or this is the start), so the claim is renewed. */
  async function run(fresh: boolean): Promise<void> {
    generation += 1;
    const mine = generation;
    controller?.abort();
    const ctl = new AbortController();
    controller = ctl;
    const live = (): boolean => generation === mine && !stopped.value;
    removed.clear();
    buffer = [];
    loading.value = true;

    try {
      let previous: TopicClaim | null = null;
      if (fresh) {
        for (const off of unlisten) {
          off();
        }
        unlisten = [...currentTopics.map((t) => hub.on(t, onPush)), hub.onReset(() => void run(false))];
        previous = claim;
        claim = null;
      }
      if (fresh && !hub.active) {
        // Snapshot first, then listen from its cursor.
        previous?.release();
        const result = await options.load(ctl.signal);
        if (!live()) {
          return;
        }
        finishLoad(result);
        if (currentTopics.length === 0) {
          return;
        }
        claim = hub.acquire(currentTopics, result.pushCursor === undefined ? {} : { cursor: result.pushCursor });
        const opened = await claim.applied;
        if (opened || result.pushCursor === undefined) {
          return;
        }
        // The stream was already running: the cursor was not used. Subscribe first, load again.
        buffer = [];
        loading.value = true;
        await hub.subscribed();
        if (!live()) {
          return;
        }
        finishLoad(await options.load(ctl.signal));
        return;
      }
      if (fresh) {
        // Topics first (the stream resumes from its last id), then the snapshot.
        claim = currentTopics.length > 0 ? hub.acquire(currentTopics) : null;
        previous?.release();
        if (claim !== null) {
          await claim.applied;
          await hub.subscribed();
        }
        if (!live()) {
          return;
        }
      }
      const result = await options.load(ctl.signal);
      if (!live()) {
        return;
      }
      finishLoad(result);
    } catch (cause) {
      if (live()) {
        error.value = cause;
        buffer = null;
        loading.value = false;
      }
    }
  }

  function begin(): void {
    currentTopics = [...toValue(options.topics)];
    void run(true);
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
    buffer = null;
  }

  whenMounted(() => {
    if (stopped.value) {
      return;
    }
    begin();
    watch(
      () => JSON.stringify(toValue(options.topics)),
      () => {
        begin();
      },
    );
  });
  if (getCurrentScope() !== undefined) {
    onScopeDispose(stop);
  }

  return { rows, status, lastAppliedAt, error, apply, reload: () => run(claim === null), stop };
}
