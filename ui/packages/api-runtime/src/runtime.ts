// One object an application creates at start-up (main.ts) and hands to @flowcatalyst/ui's plugin:
//
//   import { createApiRuntime, installSessionGuard } from "@flowcatalyst/api-runtime";  // first import
//   import { client } from "./api/client.gen";
//
//   const api = createApiRuntime({ clients: [client] });
//   installSessionGuard(router, api.session, { notPermitted: () => "/not-permitted" });
//   createApp(App).use(router).use(ui).use(flowcatalystUi, { api }).mount("#app");
import { configureClient, type ConfigurableClient, type RequestFailure } from "./client.ts";
import { ApiRequestError, toServiceError } from "./errors.ts";
import { client as authClient } from "./generated/auth/client.gen.ts";
import { getAuthSession, logout } from "./generated/auth/sdk.gen.ts";
import { createBrowserSession, type BrowserLocation, type BrowserSession, type FocusTarget } from "./session.ts";

export interface ApiRuntimeOptions {
  /** The application's generated clients (`client` from each generated folder's client.gen.ts). */
  readonly clients?: readonly ConfigurableClient[];
  /** Full-page navigation (default `window.location.assign`). */
  readonly navigate?: (url: string) => void;
  /** The current location (default `window.location`). */
  readonly location?: () => BrowserLocation;
  /** Where focus is watched (default `window`). */
  readonly focusTarget?: FocusTarget;
  /** The signed-out page used after logout when the platform gives no end-session URL. */
  readonly signedOutUrl?: string;
  /** A fetch to use instead of the global one (tests). */
  readonly fetch?: typeof fetch;
}

export interface ApiRuntime {
  readonly session: BrowserSession;
  /** Applies the runtime's settings to another generated client. */
  configure(client: ConfigurableClient): void;
  /** Listens for request failures (403, 5xx, offline); returns the function that stops listening. */
  onFailure(listener: (failure: RequestFailure) => void): () => void;
}

export function createApiRuntime(options: ApiRuntimeOptions = {}): ApiRuntime {
  const listeners = new Set<(failure: RequestFailure) => void>();
  if (options.fetch !== undefined) {
    authClient.setConfig({ fetch: options.fetch });
  }
  const session = createBrowserSession({
    read: async () => {
      const { data, error, response } = await getAuthSession();
      if (data === undefined) {
        throw new ApiRequestError(toServiceError(error, response));
      }
      return data;
    },
    signOut: async () => {
      const { data, error, response } = await logout();
      if (data === undefined) {
        throw new ApiRequestError(toServiceError(error, response));
      }
      return data;
    },
    ...(options.navigate === undefined ? {} : { navigate: options.navigate }),
    ...(options.location === undefined ? {} : { location: options.location }),
    ...(options.focusTarget === undefined ? {} : { focusTarget: options.focusTarget }),
    ...(options.signedOutUrl === undefined ? {} : { signedOutUrl: options.signedOutUrl }),
  });
  const hooks = {
    onUnauthenticated: () => {
      session.signIn();
    },
    onFailure: (failure: RequestFailure) => {
      for (const listener of listeners) {
        listener(failure);
      }
    },
  };
  const configure = (client: ConfigurableClient): void => {
    configureClient(client, hooks);
  };
  configure(authClient);
  for (const client of options.clients ?? []) {
    configure(client);
  }

  return {
    session,
    configure,
    onFailure(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}
