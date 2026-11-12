import {
	PushCore,
	type PushMessage,
	type PushStreamEventType,
	type PushStreamOptions,
	type PushStreamState,
} from "./core.ts";

// Reader for the push stream (docs/contracts/push.md sections 6 and 7):
// subscribe with the caller's credentials, listen over SSE, keep the last id,
// reconnect and resume. The logic is in core.ts (no DOM, the one source of the core, copied into
// field-js and field-react-native); this is the EventTarget face for web pages and WebViews.

export {
	DEFAULT_RETRY_MS,
	MAX_BACKOFF_MS,
	SseParser,
	topicMatches,
} from "./core.ts";
export type {
	DeniedTopic,
	EventSourceLike,
	PushMessage,
	PushStreamEventType,
	PushStreamOptions,
	PushStreamState,
	ResetDetail,
	SseMessage,
	SubscribedDetail,
} from "./core.ts";

export class PushStreamEvent<T = unknown> extends Event {
	readonly detail: T;
	constructor(type: PushStreamEventType, detail: T) {
		super(type);
		this.detail = detail;
	}
}

type Handler = (push: PushMessage) => void;

export class PushStream extends EventTarget {
	private readonly core: PushCore;

	constructor(opts: PushStreamOptions) {
		super();
		this.core = new PushCore(opts, (type, detail) => {
			this.dispatchEvent(new PushStreamEvent(type, detail));
		});
	}

	/** The id of the last push delivered (or reset seen); persist it and pass it back after a restart. */
	get lastEventId(): string | undefined {
		return this.core.lastEventId;
	}

	get state(): PushStreamState {
		return this.core.state;
	}

	get topics(): readonly string[] {
		return this.core.topics;
	}

	/** Listens for pushes on a topic or pattern (`*` one segment, final `**` the rest). Returns an unsubscribe function. */
	on(pattern: string, fn: Handler): () => void {
		return this.core.on(pattern, fn);
	}

	/** Listens for every push. Returns an unsubscribe function. */
	onAny(fn: Handler): () => void {
		return this.core.onAny(fn);
	}

	start(): void {
		this.core.start();
	}

	stop(): void {
		this.core.stop();
	}

	/** Subscribes again with a new topic set; the last id is kept. */
	setTopics(topics: string[]): void {
		this.core.setTopics(topics);
	}
}
