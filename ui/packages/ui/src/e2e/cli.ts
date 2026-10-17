// The command Playwright's web server runs (defineE2eConfig writes it):
//   node cli.ts --module examples/orders-demo --main-class ...OrdersE2e --port 4392 --control 4393 [--public http://127.0.0.1:5175]
import { parseArgs } from "node:util";
import { startBackend } from "./start-backend.ts";

const { values } = parseArgs({
  options: {
    module: { type: "string" },
    "main-class": { type: "string" },
    port: { type: "string" },
    control: { type: "string" },
    public: { type: "string" },
    root: { type: "string" },
  },
});

if (values.module === undefined || values["main-class"] === undefined || values.port === undefined || values.control === undefined) {
  console.error("usage: cli.ts --module <maven module> --main-class <launcher> --port <n> --control <n> [--public <origin>] [--root <dir>]");
  process.exit(2);
}

startBackend({
  module: values.module,
  mainClass: values["main-class"],
  port: Number(values.port),
  control: Number(values.control),
  ...(values.public === undefined ? {} : { public: values.public }),
  ...(values.root === undefined ? {} : { root: values.root }),
});
