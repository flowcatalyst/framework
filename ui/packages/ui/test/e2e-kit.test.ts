// @vitest-environment node
// The end-to-end kit's own tests (src/e2e): the pure parts. The migrated applications' Playwright suites
// (orders, tickets) are the integration proof of the rest.
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { backendCommand, defineE2eConfig } from "../src/e2e/config.ts";
import { CONTROL_PORT_ENV, control, controlOrigin, signInAs, signInQuery } from "../src/e2e/control.ts";
import { isForeignRequest } from "../src/e2e/fixtures.ts";
import { findRepositoryRoot, javaArguments, mavenArguments } from "../src/e2e/start-backend.ts";

const options = {
  port: 4392,
  control: 4393,
  backend: { module: "examples/orders-demo", mainClass: "io.example.OrdersE2e" },
} as const;

describe("defineE2eConfig", () => {
  beforeEach(() => {
    // Records the original so that the configuration's assignment is undone afterwards.
    vi.stubEnv(CONTROL_PORT_ENV, "");
  });
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("serves the suite from the application's port, waits for the control port and runs one worker", () => {
    const config = defineE2eConfig(options);
    expect(config.testDir).toBe("e2e");
    expect(config.workers).toBe(1);
    expect(config.fullyParallel).toBe(false);
    expect(config.timeout).toBe(30_000);
    expect(config.use?.baseURL).toBe("http://127.0.0.1:4392");
    expect(config.webServer).toMatchObject({ url: "http://127.0.0.1:4393/control/ready", reuseExistingServer: false });
  });

  it("puts the control port in the environment for the fixtures, and lets the timeout change", () => {
    const config = defineE2eConfig({ ...options, timeout: 60_000 });
    expect(process.env[CONTROL_PORT_ENV]).toBe("4393");
    expect(config.timeout).toBe(60_000);
  });

  it("runs the launcher through the kit's command", () => {
    const command = backendCommand(options);
    expect(command).toMatch(/^node .*[/\\]e2e[/\\]cli\.ts --module examples\/orders-demo --main-class io\.example\.OrdersE2e --port 4392 --control 4393$/);
  });
});

describe("the control client", () => {
  beforeEach(() => {
    vi.stubEnv(CONTROL_PORT_ENV, "4393");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("names its origin from the environment, and says so when the configuration did not set it", () => {
    expect(controlOrigin()).toBe("http://127.0.0.1:4393");
    vi.stubEnv(CONTROL_PORT_ENV, "");
    expect(() => controlOrigin()).toThrow(/defineE2eConfig/);
  });

  it("builds the sign-in query from what is given and nothing else", () => {
    expect(signInQuery({})).toBe("");
    expect(signInQuery({ roles: ["a:x", "b:y"] })).toBe("roles=a%3Ax%2Cb%3Ay");
    expect(signInQuery({ roles: [] })).toBe("roles=");
    expect(signInQuery({ roles: ["a:x"], name: "Ada L", client: "acme", sub: "prn_ada" })).toBe("roles=a%3Ax&name=Ada+L&client=acme&sub=prn_ada");
  });

  it("posts to the control server and answers the body", async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response("ready")));
    vi.stubGlobal("fetch", fetchMock);
    expect(await control("/control/reset")).toBe("ready");
    expect(fetchMock).toHaveBeenCalledWith("http://127.0.0.1:4393/control/reset", { method: "POST" });
    await control("/control/notifications", "GET");
    expect(fetchMock).toHaveBeenLastCalledWith("http://127.0.0.1:4393/control/notifications", { method: "GET" });
    await signInAs({ roles: ["x:y"] });
    expect(fetchMock).toHaveBeenLastCalledWith("http://127.0.0.1:4393/control/sign-in-as?roles=x%3Ay", { method: "POST" });
  });

  it("throws with the status and the launcher's message on a failure", async () => {
    vi.stubGlobal("fetch", () => Promise.resolve(new Response("no good", { status: 500 })));
    await expect(control("/control/sweep")).rejects.toThrow("control /control/sweep: 500 no good");
  });
});

describe("the CSP monitor's idea of a foreign request", () => {
  const origin = "http://127.0.0.1:4392";

  it("is anything outside the application's origin, data: URLs excepted", () => {
    expect(isForeignRequest(`${origin}/api/me`, origin, "fetch")).toBe(false);
    expect(isForeignRequest("data:image/png;base64,AAAA", origin, "image")).toBe(false);
    expect(isForeignRequest("https://api.iconify.design/lucide.json", origin, "fetch")).toBe(true);
    expect(isForeignRequest("http://127.0.0.1:4500/authorize", origin, "document")).toBe(true);
  });

  it("lets a sign-in navigation to the loopback provider through, and only that", () => {
    const options = { signInRedirects: true };
    expect(isForeignRequest("http://127.0.0.1:4500/authorize", origin, "document", options)).toBe(false);
    expect(isForeignRequest("http://127.0.0.1:4500/keys", origin, "fetch", options)).toBe(true);
    expect(isForeignRequest("https://example.com/", origin, "document", options)).toBe(true);
  });
});

describe("the backend launcher's commands", () => {
  it("builds the module and what it needs, and writes the test classpath", () => {
    expect(mavenArguments("examples/orders-demo", "/tmp/cp.txt")).toEqual([
      "-q",
      "-pl",
      "examples/orders-demo",
      "-am",
      "test-compile",
      "dependency:build-classpath",
      "-Dmdep.outputFile=/tmp/cp.txt",
      "-Dmdep.includeScope=test",
    ]);
  });

  it("starts the main class with the ports and, when given, the public origin", () => {
    const base = { module: "m", mainClass: "io.example.Main", port: 1, control: 2 };
    expect(javaArguments(base, "a:b")).toEqual(["-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", "a:b", "io.example.Main", "1", "2"]);
    expect(javaArguments({ ...base, public: "http://127.0.0.1:5175" }, "a:b").slice(-3)).toEqual(["1", "2", "http://127.0.0.1:5175"]);
  });

  it("finds the repository by its Maven wrapper, searching upwards", () => {
    const root = mkdtempSync(join(tmpdir(), "fc-e2e-kit-"));
    writeFileSync(join(root, "mvnw"), "");
    const deep = join(root, "ui", "apps", "orders");
    mkdirSync(deep, { recursive: true });
    expect(findRepositoryRoot(deep)).toBe(root);
  });
});
