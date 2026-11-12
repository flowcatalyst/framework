// One push stream for a page (docs/contracts/push.md sections 6 to 8): the application provides a hub
// once, and every component that wants pushes declares its topics. The hub keeps the union of the
// topics, reference-counted, and runs ONE PushCore (@flowcatalyst/push) for it: the stream starts when
// the first topic arrives and stops when none are left. A change to the topic set is batched into one
// `setTopics` per tick, and the core keeps its last id across it, so nothing is lost.
//
// Subscribing goes through the application's HTTP (`fetch`, default: same-origin with the session
// cookie; the framework's CSRF rule is Fetch Metadata and Origin, so no header is needed). The stream
// GET is `streamFetch` (default `globalThis.fetch`): the stream token is the capability.
import { PushCore, PushError, topicMatches } from "@flowcatalyst/push";
import type { DeniedTopic, PushMessage, PushStreamOptions, PushStreamState, ResetDetail } from "@flowcatalyst/push";
import { getCurrentInstance, getCurrentScope, inject, onMounted, onScopeDispose, provide, shallowRef, toValue, watch } from "vue";
import type { InjectionKey, MaybeRefOrGetter, ShallowRef } from "vue";
import { compareVersions } from "./version.ts";

/** The most topics one subscribe takes (contract section 6.1, `push.max-topics`). */
export const MAX_TOPICS = 100;

/** How long a list waits for the stream to confirm a new subscription before it loads anyway. */
export const DEFAULT_SUBSCRIBE_WAIT_MS = 10_000;

export interface PushHubOptions {
  /** The host's base URL; `/push/subscriptions` and `/push/stream` hang off it (e.g. `/api`). */
  readonly baseUrl: string;
  /** The subscribe call. Default: `globalThis.fetch` with `credentials: "same-origin"`. */
  readonly fetch?: typeof fetch;
  /** The stream GET. Default: `globalThis.fetch`. */
  readonly streamFetch?: typeof fetch;
  /** Mints tickets for the topics, from the application's own endpoint. */
  readonly getTickets?: (topics: string[]) => Promise<string[]>;
  readonly eventTypes?: string[];
  readonly eventSource?: PushStreamOptions["eventSource"];
  readonly random?: () => number;
  readonly sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
  /** Milliseconds `subscribed()` waits before giving up. Default 10 000. */
  readonly subscribeWaitMs?: number;
}

/** A set of topics held by one consumer. */
export interface TopicClaim {
  /** Gives the topics up (idempotent). The stream stops when the last claim is released. */
  release(): void;
  /**
   * Resolves once the topic set including this claim is applied (the tick's batch ran). With a
   * `cursor`: true when the stream was OPENED from that cursor, false when it was already running
   * (the caller must then load AFTER the topics are subscribed, contract 9.1 order).
   */
  readonly applied: Promise<boolean>;
}

export interface PushHub {
  /** The stream's state; `stopped` when no topic is held. */
  readonly state: Readonly<ShallowRef<PushStreamState>>;
  /** The latest failure (a refused subscribe, `PUSH_LIMIT` included), cleared when a stream opens or the topic set is accepted. */
  readonly error: Readonly<ShallowRef<PushError | null>>;
  /** When (ms since the epoch) the last push arrived; null before the first. */
  readonly lastPushAt: Readonly<ShallowRef<number | null>>;
  /** The topics the server refused to grant to this principal. */
  readonly denied: Readonly<ShallowRef<readonly DeniedTopic[]>>;
  /** The topic set the stream is subscribed with. */
  readonly topics: Readonly<ShallowRef<readonly string[]>>;
  /** True while at least one topic is held (a stream is running or about to). */
  readonly active: boolean;
  /** Listens for pushes on a topic or pattern (`*` one segment, final `**` the rest). Returns the unsubscribe function. */
  on(pattern: string, fn: (push: PushMessage) => void): () => void;
  /** Listens for a `reset`: the host cannot replay what was missed, so state must be loaded again. */
  onReset(fn: (detail: ResetDetail) => void): () => void;
  /**
   * Holds `topics` (reference-counted across consumers). `cursor` is a push cursor from a snapshot
   * (contract 9.1): if this claim starts the stream, the stream opens from it.
   */
  acquire(topics: readonly string[], options?: { readonly cursor?: string }): TopicClaim;
  /** Resolves when the stream has confirmed the current topic set, or the stream stopped, or the wait ran out. */
  subscribed(): Promise<void>;
  /** Stops the stream and drops every claim's effect. */
  stop(): void;
}

function isState(d: unknown): d is PushStreamState {
  return d === "connecting" || d === "open" || d === "reconnecting" || d === "stopped";
}

function isResetDetail(d: unknown): d is ResetDetail {
  return typeof d === "object" && d !== null && "reason" in d && "latest" in d && typeof d.reason === "string" && typeof d.latest === "string";
}

function isDenied(d: unknown): d is DeniedTopic[] {
  return Array.isArray(d) && d.every((x) => typeof x === "object" && x !== null && "topic" in x && "code" in x);
}

function sameSet(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) {
    return false;
  }
  const set = new Set(a);
  return b.every((t) => set.has(t));
}

function lowestCursor(cursors: readonly string[]): string | undefined {
  let best: string | undefined;
  for (const c of cursors) {
    if (best === undefined || compareVersions(c, best) < 0) {
      best = c;
    }
  }
  return best;
}

/** Creates a hub without Vue's provide/inject (tests, or code outside a component tree). */
export function createPushHub(options: PushHubOptions): PushHub {
  const subscribeFetch: typeof fetch = options.fetch ?? ((input, init) => globalThis.fetch(input, { ...init, credentials: "same-origin" }));
  const waitMs = options.subscribeWaitMs ?? DEFAULT_SUBSCRIBE_WAIT_MS;

  const state = shallowRef<PushStreamState>("stopped");
  const error = shallowRef<PushError | null>(null);
  const lastPushAt = shallowRef<number | null>(null);
  const denied = shallowRef<readonly DeniedTopic[]>([]);
  const topics = shallowRef<readonly string[]>([]);

  const counts = new Map<string, number>();
  const handlers = new Set<{ readonly pattern: string; readonly fn: (push: PushMessage) => void }>();
  const resetHandlers = new Set<(detail: ResetDetail) => void>();
  let core: PushCore | null = null;
  let launch = 0;
  let confirmed = 0;
  let scheduled = false;
  let cursors: string[] = [];
  let pending: { readonly cursor: string | undefined; readonly resolve: (used: boolean) => void }[] = [];
  let waiters: (() => void)[] = [];

  function wake(): void {
    for (const fn of [...waiters]) {
      fn();
    }
  }

  function coreOptions(initial: readonly string[], lastEventId: string | undefined): PushStreamOptions {
    return {
      baseUrl: options.baseUrl,
      topics: [...initial],
      fetch: subscribeFetch,
      ...(options.streamFetch === undefined ? {} : { streamFetch: options.streamFetch }),
      ...(options.getTickets === undefined ? {} : { getTickets: options.getTickets }),
      ...(options.eventTypes === undefined ? {} : { eventTypes: options.eventTypes }),
      ...(options.eventSource === undefined ? {} : { eventSource: options.eventSource }),
      ...(options.random === undefined ? {} : { random: options.random }),
      ...(options.sleep === undefined ? {} : { sleep: options.sleep }),
      ...(lastEventId === undefined ? {} : { lastEventId }),
    };
  }

  function dispatch(push: PushMessage): void {
    lastPushAt.value = Date.now();
    for (const h of [...handlers]) {
      if (!topicMatches(h.pattern, push.topic)) {
        continue;
      }
      try {
        h.fn(push);
      } catch (cause) {
        error.value = new PushError("HANDLER", "a push handler threw", { cause });
      }
    }
  }

  function createCore(initial: readonly string[], lastEventId: string | undefined): PushCore {
    const made: PushCore = new PushCore(coreOptions(initial, lastEventId), (type, detail) => {
      if (core !== made) {
        return;
      }
      switch (type) {
        case "state":
          if (isState(detail)) {
            state.value = detail;
            if (detail === "stopped") {
              wake();
            }
          }
          break;
        case "subscribed":
          confirmed = launch;
          error.value = null;
          wake();
          break;
        case "reset":
          if (isResetDetail(detail)) {
            for (const fn of [...resetHandlers]) {
              fn(detail);
            }
          }
          break;
        case "denied":
          if (isDenied(detail)) {
            denied.value = detail;
          }
          break;
        case "error":
          error.value = detail instanceof PushError ? detail : new PushError("NETWORK", String(detail), { cause: detail });
          break;
      }
    });
    made.onAny(dispatch);
    return made;
  }

  function stopCore(): void {
    const old = core;
    core = null;
    old?.stop();
    state.value = "stopped";
    topics.value = [];
    denied.value = [];
    wake();
  }

  function flush(): void {
    scheduled = false;
    const claims = pending;
    const claimCursors = cursors;
    pending = [];
    cursors = [];
    const desired = [...counts.keys()];
    if (desired.length === 0) {
      stopCore();
      for (const p of claims) {
        p.resolve(false);
      }
      return;
    }
    if (desired.length > MAX_TOPICS) {
      // Not truncated: the previous subscription stays, and the failure is reported.
      error.value = new PushError("PUSH_LIMIT", `${String(desired.length)} topics were asked for; one subscription takes at most ${String(MAX_TOPICS)} topics`);
      for (const p of claims) {
        p.resolve(false);
      }
      return;
    }
    if (error.value?.code === "PUSH_LIMIT" && error.value.status === undefined) {
      error.value = null;
    }
    if (core === null) {
      launch += 1;
      core = createCore(desired, lowestCursor(claimCursors));
      topics.value = desired;
      core.start();
      for (const p of claims) {
        p.resolve(true);
      }
      return;
    }
    const changed = !sameSet(topics.value, desired);
    if (changed) {
      launch += 1;
      topics.value = desired;
      denied.value = [];
      core.setTopics(desired);
    }
    if (core.state === "stopped") {
      // The core stopped itself (NO_TOPICS): a new topic set is a new attempt.
      if (!changed) {
        launch += 1;
      }
      core.start();
    }
    for (const p of claims) {
      p.resolve(p.cursor === undefined);
    }
  }

  function schedule(): void {
    if (!scheduled) {
      scheduled = true;
      queueMicrotask(flush);
    }
  }

  return {
    state,
    error,
    lastPushAt,
    denied,
    topics,
    get active(): boolean {
      return counts.size > 0;
    },
    on(pattern, fn) {
      const h = { pattern, fn };
      handlers.add(h);
      return () => handlers.delete(h);
    },
    onReset(fn) {
      resetHandlers.add(fn);
      return () => resetHandlers.delete(fn);
    },
    acquire(wanted, acquireOptions) {
      const mine = [...new Set(wanted)];
      for (const t of mine) {
        counts.set(t, (counts.get(t) ?? 0) + 1);
      }
      const cursor = acquireOptions?.cursor;
      if (cursor !== undefined) {
        cursors.push(cursor);
      }
      let released = false;
      const applied = new Promise<boolean>((resolve) => {
        pending.push({ cursor, resolve });
      });
      schedule();
      return {
        applied,
        release() {
          if (released) {
            return;
          }
          released = true;
          for (const t of mine) {
            const n = (counts.get(t) ?? 0) - 1;
            if (n <= 0) {
              counts.delete(t);
            } else {
              counts.set(t, n);
            }
          }
          schedule();
        },
      };
    },
    subscribed() {
      const ready = (): boolean => core === null || confirmed === launch || state.value === "stopped";
      if (ready()) {
        return Promise.resolve();
      }
      return new Promise<void>((resolve) => {
        const check = (): void => {
          if (ready()) {
            finish();
          }
        };
        const finish = (): void => {
          clearTimeout(timer);
          waiters = waiters.filter((w) => w !== check);
          resolve();
        };
        const timer = setTimeout(finish, waitMs);
        waiters.push(check);
      });
    },
    stop() {
      counts.clear();
      pending = [];
      cursors = [];
      stopCore();
    },
  };
}

const HUB_KEY: InjectionKey<PushHub> = Symbol("flowcatalyst-push-hub");

/**
 * Creates the page's hub and provides it to the component tree. Call it once, in the root's setup (or
 * where the application sets up its plugins); outside a component it just returns the hub.
 */
export function providePushHub(options: PushHubOptions): PushHub {
  const hub = createPushHub(options);
  if (getCurrentInstance() !== null) {
    provide(HUB_KEY, hub);
  }
  if (getCurrentScope() !== undefined) {
    onScopeDispose(() => {
      hub.stop();
    });
  }
  return hub;
}

/** The hub `providePushHub` provided. Throws when there is none. */
export function usePushHub(): PushHub {
  const hub = inject(HUB_KEY, null);
  if (hub === null) {
    throw new Error("usePushHub: no push hub; call providePushHub() in an ancestor component");
  }
  return hub;
}

/** Runs `fn` when the component is mounted, or at once when called outside a component. */
export function whenMounted(fn: () => void): void {
  if (getCurrentInstance() === null) {
    fn();
  } else {
    onMounted(fn);
  }
}

/**
 * Declares topics for the current component: held from mount, dropped on unmount (or scope end), and
 * replaced when a ref or getter changes. Topics are reference-counted across components. Returns a
 * function that drops them early. Outside a component (tests) they are held at once.
 */
export function usePushTopics(topicsSource: MaybeRefOrGetter<readonly string[]>, hub: PushHub = usePushHub()): () => void {
  let claim: TopicClaim | null = null;
  let live = true;
  const hold = (): void => {
    if (!live) {
      return;
    }
    claim?.release();
    const wanted = toValue(topicsSource);
    claim = wanted.length > 0 ? hub.acquire(wanted) : null;
  };
  let unwatch: (() => void) | null = null;
  const stop = (): void => {
    live = false;
    unwatch?.();
    claim?.release();
    claim = null;
  };
  whenMounted(() => {
    hold();
    unwatch = watch(() => JSON.stringify(toValue(topicsSource)), hold);
  });
  if (getCurrentScope() !== undefined) {
    onScopeDispose(stop);
  }
  return stop;
}
