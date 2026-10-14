// A fetch double: records every Request and answers from a script (status, JSON body).

export interface Recorded {
  readonly method: string;
  readonly url: string;
  readonly headers: Headers;
  readonly credentials: RequestCredentials;
  readonly body: string;
}

export type Answer = { readonly status: number; readonly json?: unknown; readonly text?: string } | "network-error" | "abort";

export interface FakeFetch {
  readonly fetch: typeof fetch;
  readonly requests: Recorded[];
  /** Answers for the next calls, in order; the last one repeats. */
  answer(...answers: Answer[]): void;
}

export function fakeFetch(): FakeFetch {
  const requests: Recorded[] = [];
  let script: Answer[] = [{ status: 200, json: {} }];
  const impl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const request = input instanceof Request ? input : new Request(input, init);
    requests.push({
      method: request.method,
      url: request.url,
      headers: request.headers,
      credentials: request.credentials,
      body: await request.text(),
    });
    const next = script.length > 1 ? script.shift() : script[0];
    if (next === undefined || next === "network-error") {
      throw new TypeError("Failed to fetch");
    }
    if (next === "abort") {
      throw new DOMException("The operation was aborted.", "AbortError");
    }
    const body = next.text ?? (next.json === undefined ? "" : JSON.stringify(next.json));
    return new Response(next.status === 204 ? null : body, {
      status: next.status,
      headers: { "Content-Type": next.text === undefined ? "application/json" : "text/html" },
    });
  };
  return {
    fetch: impl,
    requests,
    answer: (...answers) => {
      script = answers;
    },
  };
}
