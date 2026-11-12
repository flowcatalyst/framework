import { describe, expect, it } from "vitest";
import { compareVersions, versionGreater } from "../src/push/index.ts";

describe("push versions", () => {
  it("compares numbers and numeric strings numerically", () => {
    expect(versionGreater("10", "9")).toBe(true);
    expect(versionGreater(10, "9")).toBe(true);
    expect(versionGreater("9", 10)).toBe(false);
    expect(compareVersions(" 7 ", 7)).toBe(0);
    expect(versionGreater("1.5", "1.25")).toBe(true);
  });

  it("compares integers beyond 2^53 exactly", () => {
    expect(versionGreater("9007199254740993", "9007199254740992")).toBe(true);
    expect(versionGreater("9007199254740992", "9007199254740993")).toBe(false);
    expect(compareVersions("9007199254740993", "9007199254740993")).toBe(0);
  });

  it("falls back to strings when either side is not a number", () => {
    expect(versionGreater("b", "a")).toBe(true);
    expect(versionGreater("v10", "v9")).toBe(false); // "v1" < "v9" as strings
    expect(versionGreater("2026-11-12T10:00Z", "2026-11-12T09:00Z")).toBe(true);
    expect(compareVersions("a", "a")).toBe(0);
    expect(compareVersions("5", "x5")).toBeLessThan(0);
  });
});
