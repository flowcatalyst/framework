// A Zod schema's output marks an optional property `?: T | undefined`; hey-api writes the TypeScript
// type of the same property `?: T`. With exactOptionalPropertyTypes (the workspace's setting) the first
// is not assignable to the second, so a validated form value could not be passed to the generated SDK
// without a cast in application code. `exactOutput` is that narrowing, done once: it removes every
// `undefined`-valued property (deeply, in plain objects and arrays) and says so in the type.
//
//   const body: IngestionBody = exactOutput(zIngestionBody.parse(form));
//
// It changes nothing the server sees: JSON.stringify drops undefined-valued properties anyway.

/** `T` with `undefined` removed from every optional property, recursively. */
export type ExactOutput<T> = T extends readonly (infer U)[]
  ? ExactOutput<U>[]
  : T extends Date | ((...args: never[]) => unknown)
    ? T
    : T extends object
      ? { [K in keyof T]: ExactOutput<Exclude<T[K], undefined>> }
      : T;

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const proto: unknown = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}

function strip(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(strip);
  }
  if (isPlainObject(value)) {
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value)) {
      if (entry !== undefined) {
        out[key] = strip(entry);
      }
    }
    return out;
  }
  return value;
}

/**
 * Removes `undefined`-valued properties from a parsed value so it is assignable to a hey-api type
 * under exactOptionalPropertyTypes. Returns a copy; the input is not changed.
 */
export function exactOutput<T>(value: T): ExactOutput<T> {
  // The one assertion: `strip` returns the same shape minus the undefined-valued properties, which is
  // exactly what ExactOutput<T> says.
  // eslint-disable-next-line @typescript-eslint/consistent-type-assertions
  return strip(value) as ExactOutput<T>;
}
