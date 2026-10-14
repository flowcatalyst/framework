// Permission matching in the browser, the platform's rule as the core contract (section 3) and the
// sessions contract (section 7.3) state it: a held permission matches a required one when both have
// the same number of `:`-separated segments and each held segment is `*` or equal to the required
// one. In the browser this only decides what is shown; every operation still checks on the server.

/** True when `held` grants `required`. */
export function permissionMatches(held: string, required: string): boolean {
  const heldSegments = held.split(":");
  const requiredSegments = required.split(":");
  if (heldSegments.length !== requiredSegments.length) {
    return false;
  }
  return heldSegments.every((segment, index) => segment === "*" || segment === requiredSegments[index]);
}

/** True when any of `held` grants `required`. */
export function hasPermission(held: readonly string[], required: string): boolean {
  return held.some((permission) => permissionMatches(permission, required));
}
