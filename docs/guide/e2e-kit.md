<!-- types: E2eLauncher, TestOidcProvider, PostgresFixture, FakePlatform -->
# The browser end-to-end kit

An application with a `web/` single-page application tests it in a real browser against its real Java
backend: Playwright drives Chromium, the backend is the application itself on a Testcontainers PostgreSQL
and a test sign-in provider, and the strict Content Security Policy is on. The kit is the part of that
which does not depend on the application (plan item **MA-11**, built 2026-10-17; owner ruling: it lives in
this repository, unpublished for now). It has two halves:

| Half | Where | What it gives |
|---|---|---|
| Java | `E2eLauncher` in the `framework-auth` test-jar | the test OIDC provider with your client registered, the standard control endpoints, the `READY` handshake, orderly shutdown |
| TypeScript | `@flowcatalyst/ui/e2e` (`ui/packages/ui/src/e2e`) | `defineE2eConfig`, the CSP monitor (`test`), the control client, `openPage`, `fetchJson`, `axeViolations` |

The orders demo (`ui/apps/orders`, `OrdersE2e`) and the tickets service (`ui/apps/tickets`, `TicketsE2e`)
are the two users and the proof; the gallery uses the CSP monitor and the axe helper without a backend.
Nothing here touches a developer's database or port 8080: the ports are the ones you choose, the database
is a container and the platform is a fake.

## Adding it to an application: five steps

**1. The package script** (in the application's `package.json`; the kit needs `@playwright/test` and
`@axe-core/playwright` as dev dependencies, the peer dependencies of `@flowcatalyst/ui`):

```json
"e2e": "vite build && playwright test"
```

`vite build` writes the SPA into the module's `web/dist`; Maven copies it into the classes, so the backend
serves the build you just made.

**2. The configuration** (`playwright.config.ts`). The two ports are yours and must be free of 8080, 15432
and the other applications' (taken: 4391 gallery, 4392 and 4393 orders, 4394 and 4395 tickets, 4396 and 4397 the Camel
application, Vite 5173 to 5176):

```ts
import { defineE2eConfig } from "@flowcatalyst/ui/e2e";

export default defineE2eConfig({
  port: 4392,
  control: 4393,
  backend: { module: "examples/orders-demo", mainClass: "io.flowcatalyst.framework.examples.ordersdemo.OrdersE2e" },
});
```

It sets one worker (the tests share one database), a 30 s test timeout (`timeout` changes it), Chromium, a
trace kept on failure, and the web server: the kit's command builds the module and what it needs with
`./mvnw test-compile` (set `MAVEN_ARGS=-o` to build offline), writes the test classpath, runs the launcher
and waits for `GET /control/ready` on the control port for up to five minutes. It also puts the control port
in `FC_E2E_CONTROL_PORT` for the fixtures.

**3. The launcher** (a class with `main` in the application's test sources, so the test-jars are on its
classpath). `OrdersE2e` is the model:

```java
E2eLauncher e2e = E2eLauncher.start(args, "orders-web");
PostgresFixture pg = e2e.own(PostgresFixture.start());
FakePlatform platform = e2e.own(DemoPlatform.start());
e2e.signInAs(E2eLauncher.person("orders-demo:clerk", "Ada Lovelace"));

Map<String, String> env = e2e.environment();
env.put("DATABASE_INSTANCES_MAIN_URL", pg.jdbcUrl());
// ... the application's own entries
e2e.own(OrdersWeb.builder(...).configDirectory(Path.of("config")).environment(env).start());

e2e.endSessions(() -> pg.execute("DELETE FROM fw_session"))
    .control("/control/reset", q -> {
      pg.execute("TRUNCATE order_lines, orders, outbox_messages CASCADE");
      return "ok";
    })
    .serve();
```

- `start(args, clientId)` reads `<app-port> <control-port> [<public-origin>]`, starts the provider, registers
  the client with the redirect `<origin>/auth/callback` and points the end-session redirect at the SPA's
  `/signed-out`. A third argument is the origin the browser sees when something else fronts the backend
  (the Vite dev server). `start(args, clientId, clock)` makes the provider follow a clock your suite moves
  (the tickets suite does, and passes the same clock to its `App`).
- `environment()` returns a fresh map with `APP_ENV=test`, `HTTP_HOST`, `HTTP_PORT`, `AUTH_ISSUER`,
  `AUTH_CLIENT_ID` and `AUTH_PUBLIC_BASE_URL`; add the database, platform and other entries.
- `own(resource)` hands over what must be closed on shutdown (the database fixture, fakes, the `App`); they
  are closed last first, then the provider.
- `control(path, handler)` registers your endpoint under `/control/`. The handler gets the decoded query and
  returns the body of the `200`; an exception becomes a `500` whose body is its message, which the
  TypeScript client puts in its error. Any method is accepted.
- `serve()` starts the control server, prints `READY` and waits; the JVM's shutdown hook closes everything.

**4. The fixtures** (`e2e/fixtures.ts`): re-export what the specs use and add the application's roles and
helpers.

```ts
import { control, expect, fetchJson, openPage, signInAs as signIn, test } from "@flowcatalyst/ui/e2e";

export { control, expect, fetchJson, openPage, test };
export const CLERK = "orders-demo:clerk";

export async function signInAs(...roles: string[]): Promise<void> {
  await signIn({ roles });
}

export async function freshData(): Promise<void> {
  await control("/control/reset");
  await signInAs(CLERK);
}
```

**5. A spec.** Every test gets the CSP monitor automatically (the `csp` fixture); `await csp.expectClean()`
asserts no violation, no console message about the policy, no request to another origin and no uncaught page
error. Strict axe is one call:

```ts
import { axeViolations } from "@flowcatalyst/ui/e2e";
import { expect, freshData, openPage, test } from "./fixtures.ts";

test("the list is accessible", async ({ page, csp }) => {
  await freshData();
  await openPage(page, "/orders", "Orders");
  expect(await axeViolations(page)).toEqual([]);
  await csp.expectClean();
});
```

## The standard control endpoints

Served by `E2eLauncher` for every application (the TypeScript names are in `@flowcatalyst/ui/e2e`):

| Endpoint | Effect | TypeScript |
|---|---|---|
| `/control/ready` | `200 ready`; the web server's probe | (used by `defineE2eConfig`) |
| `/control/sign-in-as?roles=a,b&name=&client=&sub=` | the next sign-in is that person; with neither `client` nor `sub`, a person of no client (`E2eLauncher.person(roles, name)`), otherwise one client's user (`person(roles, name, client, sub)`) | `signInAs({ roles, name, client, sub })`, `switchTo(page, who)` |
| `/control/provider-down?value=true` | the provider answers 503 | `providerDown(true)` |
| `/control/authorize-error?value=access_denied` | the provider refuses the next sign-ins (empty value: normal) | `authorizeError(code)` |
| `/control/end-sessions` | registered by `endSessions(action)`: delete every session, as an expiry would | `endSessions()` |

Everything else (truncate and seed, moving a clock, playing the platform's part) is the application's own,
registered with `control(...)` and called with `control(path)` (`control(path, "GET")` for a read).

## The CSP monitor

`test` is Playwright's `test` with an automatic `csp` fixture, installed before any page script runs. A
request to another origin is a failure, except the top-level navigation to the loopback sign-in provider,
which the round trip needs. `createTest()` builds the strict variant for an application with no backend
(the gallery: no request may leave its origin at all). The pure part, `isForeignRequest`, is unit-tested.

## What is tested, and what is not

The kit's own tests are unit tests: `E2eLauncherTest` (the endpoints over a real socket, the claims, closing
order) and `ui/packages/ui/test/e2e-kit.test.ts` (the configuration, the control client, the monitor's
classifier, the Maven and Java commands). The integration proof is the orders and tickets suites, which run
on the kit.

Not covered: browsers other than Chromium, a real platform, more than one worker, and publishing: the kit is
consumed with `link:` like the rest of `@flowcatalyst/ui`, and the Java side by an application that builds in
this repository (an application elsewhere needs the framework installed and its own copy of the launcher's
test-jar dependencies; see [the migrating guide](migrating-the-ingest-control-plane.md), gap 1).
