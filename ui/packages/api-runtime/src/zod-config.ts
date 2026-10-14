// Zod 4 compiles object parsers with `new Function` when it may, and probes for that by calling
// `new Function("")` the first time an object schema is built. Under the strict CSP
// (`script-src` without 'unsafe-eval'; docs/contracts/static-assets-and-spa.md section 7) the probe
// is caught by Zod but still reported as a violation. `jitless` skips both.
//
// This module runs it. It must be evaluated before any schema exists, so an application imports
// `@flowcatalyst/api-runtime` (whose first import is this module) or `@flowcatalyst/api-runtime/zod`
// as the FIRST import of its entry (main.ts), before its router, pages or generated zod.gen.ts.
//
// It also sets the messages a user reads next to a field. Zod's own ("Invalid string: must match
// pattern /^(?:[A-Z0-9][A-Z0-9-]*)$/", "Too small: expected string to have >=1 characters") are for
// developers; the generated schemas carry the server's rules, and the form shows what the rule means.
import { config, type core } from "zod";

const FORMAT_MESSAGES: Readonly<Record<string, string>> = {
  email: "Enter a valid email address.",
  url: "Enter a valid URL.",
  regex: "Enter a value in the expected format.",
};

/** The message of one Zod issue as a user reads it; undefined keeps Zod's own. */
export function userMessage(issue: core.$ZodRawIssue): string | undefined {
  if (issue.code === "invalid_type") {
    return issue.input === undefined || issue.input === null ? "This field is required." : "Enter a valid value.";
  }
  if (issue.code === "too_small") {
    if (issue.origin === "string") {
      return issue.minimum === 1 || issue.minimum === 1n ? "This field is required." : `Enter at least ${String(issue.minimum)} characters.`;
    }
    if (issue.origin === "array" || issue.origin === "set") {
      return `Choose at least ${String(issue.minimum)}.`;
    }
    return issue.inclusive === false ? `Must be more than ${String(issue.minimum)}.` : `Must be at least ${String(issue.minimum)}.`;
  }
  if (issue.code === "too_big") {
    if (issue.origin === "string") {
      return `Enter at most ${String(issue.maximum)} characters.`;
    }
    if (issue.origin === "array" || issue.origin === "set") {
      return `Choose at most ${String(issue.maximum)}.`;
    }
    return issue.inclusive === false ? `Must be less than ${String(issue.maximum)}.` : `Must be at most ${String(issue.maximum)}.`;
  }
  if (issue.code === "invalid_format") {
    return FORMAT_MESSAGES[issue.format] ?? "Enter a valid value.";
  }
  if (issue.code === "invalid_value") {
    return "Choose one of the listed values.";
  }
  return undefined;
}

config({ jitless: true, customError: userMessage });

/** True once this module has run (for tests and start-up assertions). */
export const zodJitless = true;
