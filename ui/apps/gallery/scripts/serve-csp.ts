// A tiny static server for the built gallery with the strict security headers of
// docs/contracts/static-assets-and-spa.md (sections 4, 6 and 7), for the Playwright CSP checks.
// It follows the contract's answer table closely enough for a browser (index fallback for client
// routes, 404 for a missing asset, immutable hashed assets); it is not the conformance
// implementation (Java's and Go's are).
//
//   node scripts/serve-csp.ts [--port 4391] [--dir dist]
import { createHash } from "node:crypto";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, relative, sep } from "node:path";
import { parseArgs } from "node:util";
import { strictSecurityHeaders } from "@flowcatalyst/ui/csp";

const { values } = parseArgs({
  options: { port: { type: "string", default: "4391" }, dir: { type: "string", default: "dist" } },
});
const root = join(process.cwd(), values.dir);

const CONTENT_TYPES: Readonly<Record<string, string>> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".map": "application/json",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
};
const ASSET_EXTENSION = /\.(js|mjs|cjs|css|map|json|webmanifest|txt|xml|wasm|html|htm|png|jpe?g|gif|svg|ico|webp|avif|bmp|woff2?|ttf|otf|eot)$/i;

interface Entry {
  readonly body: Buffer;
  readonly type: string;
  readonly etag: string;
}

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    if (name.startsWith(".")) {
      return [];
    }
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

const index = new Map<string, Entry>();
for (const file of walk(root)) {
  const body = readFileSync(file);
  const etag = `"${createHash("sha256").update(body).digest("hex").slice(0, 32)}"`;
  index.set(relative(root, file).split(sep).join("/"), {
    body,
    etag,
    type: CONTENT_TYPES[extname(file).toLowerCase()] ?? "application/octet-stream",
  });
}
const indexHtml = index.get("index.html");
if (indexHtml === undefined) {
  throw new Error(`${root}/index.html not found: run the build first`);
}

const security = strictSecurityHeaders();

createServer((request, response) => {
  for (const [name, value] of Object.entries(security)) {
    response.setHeader(name, value);
  }
  const path = decodeURIComponent(new URL(request.url ?? "/", "http://localhost").pathname).slice(1);
  const notFound = (): void => {
    response.writeHead(404, { "Content-Type": "application/json", "Cache-Control": "no-store" });
    response.end(JSON.stringify({ error: "NOT_FOUND", message: "no route for this path" }));
  };
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD", "Content-Type": "application/json", "Cache-Control": "no-store" });
    response.end(JSON.stringify({ error: "METHOD_NOT_ALLOWED", message: "method not allowed" }));
    return;
  }
  if (path.split("/").some((segment) => segment === ".." || segment.startsWith("."))) {
    notFound();
    return;
  }
  const file = path === "" ? indexHtml : index.get(path);
  if (file === undefined && (path.startsWith("assets/") || path.startsWith("api/") || ASSET_EXTENSION.test(path))) {
    notFound();
    return;
  }
  const entry = file ?? indexHtml;
  const cache = path.startsWith("assets/") && file !== undefined ? "public, max-age=31536000, immutable" : "no-cache";
  response.writeHead(200, { "Content-Type": entry.type, "Cache-Control": cache, ETag: entry.etag });
  response.end(request.method === "HEAD" ? undefined : entry.body);
}).listen(Number(values.port), "127.0.0.1", () => {
  console.log(`gallery with the strict CSP on http://127.0.0.1:${values.port}`);
});
