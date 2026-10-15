// What a person is told for each code of a failed sign-in (sessions contract 4.2 and 4.1.1: the callback
// answers one of the first seven; `/auth/login` can also fail with PLATFORM_UNAVAILABLE or RATE_LIMITED
// for a browser navigation; an application's `LoginFailureHandler.redirectTo(page)` puts the code in
// `?error=`). Some are worth trying again, none shows a token or the platform's own text. The codes
// are fixed by the contract, so the sentences live in the kit, not in each application.
export const SIGN_IN_FAILURE_MESSAGES: Readonly<Record<string, string>> = {
  LOGIN_REFUSED: "The sign-in was refused. You may not have access to this application.",
  LOGIN_NOT_STARTED_HERE: "This sign-in was not started in this browser. Start it again from here.",
  INVALID_STATE: "The sign-in took too long or was already used. Start it again.",
  INVALID_CALLBACK: "The sign-in answer was incomplete. Start it again.",
  LOGIN_FAILED: "The platform did not accept the sign-in. Try again in a moment.",
  INVALID_ID_TOKEN: "The platform's answer could not be verified. Try again, and tell an administrator if it repeats.",
  PLATFORM_UNAVAILABLE: "The sign-in service is not reachable right now. Try again in a moment.",
  RATE_LIMITED: "Too many sign-in attempts from this address. Wait a minute, then try again.",
};

/** The sentence for a code; any other code (or none) gets the general one. */
export function signInFailureMessage(code: string): string {
  return SIGN_IN_FAILURE_MESSAGES[code] ?? "The sign-in did not complete. Try again.";
}
