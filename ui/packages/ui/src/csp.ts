// Content-Security-Policy support for an SPA built on @flowcatalyst/ui
// (docs/contracts/static-assets-and-spa.md section 7, docs/go/ui.md sections 5 and 9).
//
// This module has no imports, so a test server, a build script and the browser code can all use it.

/** localStorage key holding the colour-mode choice: "light", "dark" or "auto" (absent = "auto"). */
export const COLOR_MODE_STORAGE_KEY = "fc-color-mode";

/**
 * The only inline script an SPA built on this package has: it puts `class="dark"` on `<html>`
 * before the first paint, so a dark page does not flash light. `flowcatalystUi()` (the Vite plugin)
 * writes it into `index.html` byte for byte; the strict CSP allows it by its hash.
 *
 * Changing one character changes the hash: change {@link COLOR_MODE_SNIPPET_SHA256} with it (a
 * unit test fails otherwise) and tell the servers that pass it (Java `Csp.strict(...)`, Go
 * `spa.Options`).
 */
export const COLOR_MODE_SNIPPET =
  '(function(){try{var m=localStorage.getItem("fc-color-mode");' +
  'if(m==="dark"||(m!=="light"&&window.matchMedia("(prefers-color-scheme: dark)").matches))' +
  '{document.documentElement.classList.add("dark")}}catch(e){}})();';

/** `'sha256-...'` source expression of {@link COLOR_MODE_SNIPPET}, for `script-src`. */
export const COLOR_MODE_SNIPPET_SHA256 = "'sha256-xo9261yOpFM47x0zwjr2iUF9sFaw/YoSkqnnzaDaOC8='";

/**
 * The strict policy of the static-assets contract, section 7, with the given script hashes
 * (`'sha256-...'` source expressions). No `'unsafe-inline'`, no nonce, no style hash.
 */
export function strictContentSecurityPolicy(
  scriptHashes: readonly string[] = [COLOR_MODE_SNIPPET_SHA256],
): string {
  const scriptSrc = ["'self'", ...scriptHashes].join(" ");
  return [
    "default-src 'self'",
    `script-src ${scriptSrc}`,
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "frame-ancestors 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "object-src 'none'",
  ].join("; ");
}

/** Every header the strict mode sends on the SPA's responses (contract sections 6.1 and 7). */
export function strictSecurityHeaders(
  scriptHashes: readonly string[] = [COLOR_MODE_SNIPPET_SHA256],
): Readonly<Record<string, string>> {
  return {
    "Content-Security-Policy": strictContentSecurityPolicy(scriptHashes),
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "same-origin",
    "X-Frame-Options": "DENY",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
  };
}
