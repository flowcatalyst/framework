// Optional form fields under `exactOptionalPropertyTypes`. A generated optional field (`include?: string`)
// cannot be bound to Nuxt UI's UInput / UTextarea (they take a string, never `undefined`) or UInputNumber
// (a number or null). These adapters show a missing value as "" (or null) and remove the property when
// the field is emptied, so the form state always matches the generated type and never carries an
// explicit `undefined`. Pass the *reactive* state object (reactive(), or a ref's .value).
import { computed, type WritableComputedRef } from "vue";

export interface OptionalTextOptions {
  /** Trim what is typed before it is stored; a blank entry then counts as empty. Default false. */
  readonly trim?: boolean;
}

/**
 * Two-way binding for an optional string property: `v-model="include"` on a UInput or UTextarea.
 * Reads "" when the property is missing; writing "" (or blank, with `trim`) deletes the property.
 */
export function optionalText<K extends string>(
  target: { [P in K]?: string },
  key: K,
  options: OptionalTextOptions = {},
): WritableComputedRef<string> {
  const trim = options.trim === true;
  return computed({
    get: () => target[key] ?? "",
    set: (value: string) => {
      const next = trim ? value.trim() : value;
      if (next === "") {
        Reflect.deleteProperty(target, key);
      } else {
        target[key] = next;
      }
    },
  });
}

/**
 * Two-way binding for an optional number property: `v-model="limit"` on a UInputNumber. Reads
 * `null` when the property is missing; writing `null` (or NaN, which UInputNumber emits for an
 * unreadable entry) deletes the property.
 */
export function optionalNumber<K extends string>(
  target: { [P in K]?: number },
  key: K,
): WritableComputedRef<number | null> {
  return computed({
    get: () => target[key] ?? null,
    set: (value: number | null) => {
      if (value === null || Number.isNaN(value)) {
        Reflect.deleteProperty(target, key);
      } else {
        target[key] = value;
      }
    },
  });
}
