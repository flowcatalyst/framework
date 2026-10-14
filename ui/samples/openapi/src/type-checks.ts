// Compile-time checks of what @hey-api/openapi-ts generates from openapi.json (U0 open question 2,
// docs/go/ui.md section 18 and docs/contracts/sessions-and-login.md section 4.5). Every line is a
// type assertion: `tsc --build` fails if the generated types change shape.
import { createIngestion, getAuthSession, listIngestions } from "./api/sdk.gen.ts";
import type { AuthCsrf, AuthLogout, AuthSession, CreateIngestionErrors, IngestionView, Source } from "./api/types.gen.ts";

// Mutual assignability; with exactOptionalPropertyTypes `x?: T` and `x?: T | undefined` differ.
type Equal<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
type Expect<T extends true> = T;

// 1. `const` is rendered as a literal type, not as `string` / `boolean`.
export type ConstChecks = [
  Expect<Equal<AuthLogout["loggedOut"], true>>,
  Expect<Equal<AuthCsrf["mode"], "fetch-metadata">>,
  Expect<Equal<AuthCsrf["jsonBodies"], true>>,
];

// 2. oneOf without a discriminator keyword narrows on the `const` boolean.
export function describeSession(session: AuthSession): string {
  if (session.authenticated) {
    return `${session.principal.id} (${session.principal.kind}) holds ${String(session.permissions.length)} permissions`;
  }
  return `anonymous; sign in at ${session.loginUrl}`;
}

// 3. oneOf + discriminator narrows on the discriminator, exhaustively.
export function describeSource(source: Source): string {
  switch (source.kind) {
    case "database-poll":
      return `poll ${source.query} every ${source.every}`;
    case "http":
      return `receive on ${source.path}`;
  }
}

// 4. Enums are literal unions (no TypeScript `enum`, so erasableSyntaxOnly holds).
export type EnumChecks = [Expect<Equal<IngestionView["status"], "ACTIVE" | "PAUSED" | "RETIRED">>];

// 5. "May be absent" is `?:` without `| undefined` (exactOptionalPropertyTypes distinguishes them).
export type OptionalChecks = [
  Expect<Equal<AuthLogout, { loggedOut: true; endSessionUrl?: string }>>,
];

// 6. Declared error statuses are typed with the error envelope.
export type ErrorChecks = [Expect<Equal<CreateIngestionErrors[409]["error"], string>>];

// 7. The whole call is typed: path, query, body. (Not executed; compiled only.)
export async function typedCalls(): Promise<void> {
  const page = await listIngestions({ query: { status: ["ACTIVE"] } });
  if (page.data !== undefined) {
    page.data.items.map((item) => item.code);
  }
  await createIngestion({
    body: {
      code: "orders",
      name: "Orders",
      source: { kind: "http", path: "/in/orders" },
      retries: 3,
      tags: [],
      labels: {},
      amount: "10.00",
      startsOn: "2026-10-14",
      timeout: "PT5M",
    },
  });
  const session = await getAuthSession();
  if (session.data?.authenticated === true) {
    session.data.permissions.includes("x");
  }
}
