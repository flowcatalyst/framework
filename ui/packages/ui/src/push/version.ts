// The version rule of the live composables: a row or record is replaced only by a higher version.
// A version is a number or a string. When both parse as numbers they are compared numerically
// (integers exactly, however large: "9007199254740993" is higher than "9007199254740992"); otherwise
// they are compared as strings, code unit by code unit.

/** A row's or record's version, as the application's API carries it. */
export type Version = number | string;

function numeric(v: Version): bigint | number | null {
  if (typeof v === "number") {
    return Number.isFinite(v) ? v : null;
  }
  const text = v.trim();
  if (/^-?\d+$/.test(text)) {
    return BigInt(text);
  }
  if (/^-?(\d+\.?\d*|\.\d+)(e[+-]?\d+)?$/i.test(text)) {
    const n = Number(text);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/** Negative when `a` is lower than `b`, zero when they are equal, positive when `a` is higher. */
export function compareVersions(a: Version, b: Version): number {
  const x = numeric(a);
  const y = numeric(b);
  if (x !== null && y !== null) {
    if (x < y) {
      return -1;
    }
    return x > y ? 1 : 0;
  }
  const sa = String(a);
  const sb = String(b);
  if (sa < sb) {
    return -1;
  }
  return sa > sb ? 1 : 0;
}

/** Whether `a` is higher than `b`. */
export function versionGreater(a: Version, b: Version): boolean {
  return compareVersions(a, b) > 0;
}
