import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { COLOR_MODE_SNIPPET, COLOR_MODE_SNIPPET_SHA256 } from "../src/csp.ts";
import { CSP_HASHES_FILE, cspHashesJson, flowcatalystUi, inlineScriptHashes, nuxtUiOptions, writeCspHashes } from "../src/vite.ts";

// The build writes the hashes of index.html's inline scripts to csp-hashes.json, which the Java `Spa`
// (and any server) reads for its strict policy: the hash is no longer copied into application code (U3 G1).

const sha = (s: string) => `sha256-${createHash("sha256").update(s, "utf8").digest("base64")}`;

describe("inlineScriptHashes", () => {
  it("hashes each inline script's exact text", () => {
    const html = `<head><script>${COLOR_MODE_SNIPPET}</script><script type="module">import "x"</script></head>`;
    expect(inlineScriptHashes(html)).toEqual([sha(COLOR_MODE_SNIPPET), sha('import "x"')]);
  });

  it("the colour-mode snippet's hash is the published constant", () => {
    expect(inlineScriptHashes(`<script>${COLOR_MODE_SNIPPET}</script>`)).toEqual([
      COLOR_MODE_SNIPPET_SHA256.slice(1, -1),
    ]);
  });

  it("skips scripts with a src and data blocks that never run", () => {
    const html =
      '<script type="module" crossorigin src="/assets/index-1.js"></script>' +
      '<script type="application/json">{"a":1}</script>' +
      '<script type="application/ld+json">{}</script>' +
      "<script>run()</script>";
    expect(inlineScriptHashes(html)).toEqual([sha("run()")]);
  });

  it("lists a repeated script once and none for a page without inline scripts", () => {
    expect(inlineScriptHashes("<script>a()</script><script>a()</script>")).toEqual([sha("a()")]);
    expect(inlineScriptHashes("<div></div>")).toEqual([]);
  });
});

describe("the Vite plugin's csp-hashes.json", () => {
  let out: string;
  beforeEach(() => {
    out = mkdtempSync(join(tmpdir(), "fc-csp-"));
  });
  afterEach(() => {
    rmSync(out, { recursive: true, force: true });
  });

  it("is written beside index.html with the hash of the snippet the plugin injected", () => {
    const html = `<!doctype html><head><script>${COLOR_MODE_SNIPPET}</script></head><body></body>`;
    writeCspHashes(out, { "index.html": { type: "asset", fileName: "index.html", source: html } });
    const written: unknown = JSON.parse(readFileSync(join(out, CSP_HASHES_FILE), "utf8"));
    expect(written).toEqual({ scriptHashes: [COLOR_MODE_SNIPPET_SHA256.slice(1, -1)] });
  });

  it("covers every html page of the bundle, bytes or text, and ignores chunks", () => {
    writeCspHashes(out, {
      "index.html": { type: "asset", fileName: "index.html", source: "<script>a()</script>" },
      "other.html": { type: "asset", fileName: "other.html", source: new TextEncoder().encode("<script>b()</script>") },
      "assets/x.js": { type: "chunk", fileName: "assets/x.js" },
      "assets/notes.txt": { type: "asset", fileName: "assets/notes.txt", source: "<script>z()</script>" },
    });
    expect(JSON.parse(readFileSync(join(out, CSP_HASHES_FILE), "utf8"))).toEqual({
      scriptHashes: [sha("a()"), sha("b()")],
    });
  });

  it("the plugin has the hook", () => {
    expect(typeof flowcatalystUi(nuxtUiOptions()).writeBundle).toBe("function");
  });

  it("is JSON the servers read: an object with scriptHashes", () => {
    expect(JSON.parse(cspHashesJson(["sha256-AbC="]))).toEqual({ scriptHashes: ["sha256-AbC="] });
  });
});
