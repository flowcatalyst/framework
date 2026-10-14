// Fails a full Playwright run in which a row of the behaviour checklist (src/behaviours.ts) is not
// claimed by a passing test (`covers(...)`, e2e/fixtures.ts). A run that does not include
// e2e/behaviours.spec.ts (one file, a --grep, the screenshot run) is not checked.
import type { FullResult, Reporter, Suite, TestCase, TestResult } from "@playwright/test/reporter";
import { behaviours } from "../src/behaviours.ts";

export default class BehaviourReporter implements Reporter {
  private full = false;
  private readonly exercised = new Set<string>();

  onBegin(_config: unknown, suite: Suite): void {
    this.full = suite.allTests().some((test) => test.location.file.endsWith("behaviours.spec.ts"));
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    if (result.status !== "passed") {
      return;
    }
    for (const annotation of test.annotations) {
      if (annotation.type === "behaviour" && annotation.description !== undefined) {
        this.exercised.add(annotation.description);
      }
    }
  }

  // Playwright reads the returned status (a Promise, per the Reporter type) as the run's result.
  onEnd(): Promise<{ status: FullResult["status"] } | undefined> {
    if (!this.full) {
      return Promise.resolve(undefined);
    }
    const missing = behaviours.filter((row) => !this.exercised.has(row.id)).map((row) => row.id);
    if (missing.length > 0) {
      console.error(`\nBehaviour checklist: not exercised by a passing test: ${missing.join(", ")}`);
      return Promise.resolve({ status: "failed" });
    }
    console.log(`\nBehaviour checklist: all ${String(behaviours.length)} behaviours exercised by passing tests.`);
    return Promise.resolve(undefined);
  }
}
