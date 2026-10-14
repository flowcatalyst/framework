import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "happy-dom",
    // A page origin, so the clients' same-origin relative URLs resolve as in a browser.
    environmentOptions: { happyDOM: { url: "http://app.example.test/orders?status=OPEN#top" } },
    include: ["test/**/*.test.ts"],
  },
});
