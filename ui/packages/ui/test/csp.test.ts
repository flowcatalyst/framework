import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import {
  COLOR_MODE_SNIPPET,
  COLOR_MODE_SNIPPET_SHA256,
  strictContentSecurityPolicy,
  strictSecurityHeaders,
} from "../src/csp.ts";

describe("csp", () => {
  it("the colour-mode snippet's hash is the published constant", () => {
    const digest = createHash("sha256").update(COLOR_MODE_SNIPPET, "utf8").digest("base64");
    expect(COLOR_MODE_SNIPPET_SHA256).toBe(`'sha256-${digest}'`);
  });

  it("the strict policy is the contract's, with no inline allowance", () => {
    expect(strictContentSecurityPolicy()).toBe(
      "default-src 'self'; " +
        `script-src 'self' ${COLOR_MODE_SNIPPET_SHA256}; ` +
        "style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; " +
        "frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'",
    );
    expect(strictContentSecurityPolicy()).not.toContain("unsafe");
  });

  it("strict headers carry the contract's fixed headers", () => {
    expect(strictSecurityHeaders()).toMatchObject({
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "same-origin",
      "X-Frame-Options": "DENY",
      "Cross-Origin-Opener-Policy": "same-origin",
      "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    });
  });
});
