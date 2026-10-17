// optionalText / optionalNumber: optional generated fields bound to inputs that never take undefined.
import { describe, expect, expectTypeOf, it } from "vitest";
import { isRef, nextTick, reactive, watchEffect } from "vue";
import { optionalNumber, optionalText } from "../src/composables/useOptionalFields.ts";

// The shape hey-api generates under exactOptionalPropertyTypes: `?: T`, never `?: T | undefined`.
interface Filter {
  name: string;
  include?: string;
  limit?: number;
}

describe("optionalText", () => {
  it("shows a missing value as an empty string", () => {
    const state = reactive<Filter>({ name: "a" });
    const include = optionalText(state, "include");
    expect(include.value).toBe("");
    expect(isRef(include)).toBe(true);
  });

  it("stores typed text and drops the property when emptied (no explicit undefined)", () => {
    const state = reactive<Filter>({ name: "a" });
    const include = optionalText(state, "include");
    include.value = "x";
    expect(state.include).toBe("x");
    include.value = "";
    expect("include" in state).toBe(false);
    expect(JSON.stringify(state)).toBe('{"name":"a"}');
  });

  it("trims when asked, so blank counts as empty", () => {
    const state = reactive<Filter>({ name: "a", include: "keep" });
    const include = optionalText(state, "include", { trim: true });
    include.value = "  ";
    expect("include" in state).toBe(false);
    include.value = " y ";
    expect(state.include).toBe("y");
    const raw = optionalText(state, "include");
    raw.value = " z ";
    expect(state.include).toBe(" z ");
  });

  it("is reactive in both directions", async () => {
    const state = reactive<Filter>({ name: "a" });
    const include = optionalText(state, "include");
    const seen: string[] = [];
    watchEffect(() => seen.push(include.value));
    include.value = "q";
    await nextTick();
    delete state.include;
    await nextTick();
    expect(seen).toEqual(["", "q", ""]);
  });

  it("types: a string in and out, only optional string keys", () => {
    const state = reactive<Filter>({ name: "a" });
    expectTypeOf(optionalText(state, "include").value).toEqualTypeOf<string>();
    // @ts-expect-error: `limit` is a number, not a string
    optionalText(state, "limit");
  });
});

describe("optionalNumber", () => {
  it("shows a missing value as null, stores numbers and drops the property for null or NaN", () => {
    const state = reactive<Filter>({ name: "a" });
    const limit = optionalNumber(state, "limit");
    expect(limit.value).toBeNull();
    limit.value = 0;
    expect(state.limit).toBe(0);
    expect(limit.value).toBe(0);
    limit.value = null;
    expect("limit" in state).toBe(false);
    limit.value = 5;
    limit.value = Number.NaN;
    expect("limit" in state).toBe(false);
    expect(JSON.stringify(state)).toBe('{"name":"a"}');
  });

  it("types: number | null", () => {
    const state = reactive<Filter>({ name: "a" });
    expectTypeOf(optionalNumber(state, "limit").value).toEqualTypeOf<number | null>();
    // @ts-expect-error: `include` is a string, not a number
    optionalNumber(state, "include");
  });
});
