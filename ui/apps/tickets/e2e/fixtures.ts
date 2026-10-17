// The tickets application's end-to-end helpers on the shared kit (@flowcatalyst/ui/e2e, docs/guide/e2e-kit.md):
// the kit provides the `test` with the CSP monitor, the control server's client, `openPage` and `fetchJson`;
// this file adds the service's roles, its default person (an agent of acme) and its ticket helpers. The
// Java side is TicketsE2e in the service's tests (it also moves the clock and plays the platform's part:
// fire the SLA sweep, deliver the assignment events).
import { control, expect, fetchJson, openPage, signInAs as signIn, test, type SignInAs } from "@flowcatalyst/ui/e2e";
import type { Page } from "@playwright/test";

export { control, expect, fetchJson, openPage, test };

/** The roles of the service (Roles): who may open, who works, who also sets the SLA. */
export const REQUESTER = "tickets-trial:requester";
export const AGENT = "tickets-trial:agent";
export const LEAD = "tickets-trial:lead";
/** The administrator of the admin area. */
export const ADMIN = "tickets-trial:admin";

export type Who = SignInAs;

/** Who signs in unless the test says otherwise: Ada Lovelace, an agent of acme, prn_ada. */
function person(who: Who): Required<Who> {
  return { roles: who.roles ?? [AGENT], client: who.client ?? "acme", sub: who.sub ?? "prn_ada", name: who.name ?? "Ada Lovelace" };
}

/** The next sign-in is this person (default: Ada Lovelace, an agent of acme, prn_ada). */
export async function signInAs(who: Who = {}): Promise<void> {
  await signIn(person(who));
}

/** Signs the browser in again as someone else: the next sign-in is theirs and the cookies are gone. */
export async function switchTo(page: Page, who: Who): Promise<void> {
  await signInAs(who);
  await page.context().clearCookies();
}

/** Empties the tickets and makes the next sign-in an agent of acme. */
export async function freshData(): Promise<void> {
  await control("/control/reset");
  await signInAs();
}

export interface TicketSummary {
  readonly id: string;
  readonly subject: string;
}

/** Opens a ticket as the signed-in page does (same-origin fetch: the CSRF rule needs the browser). */
export async function openTicket(page: Page, subject: string, priority: "LOW" | "NORMAL" | "HIGH" | "URGENT" = "NORMAL"): Promise<TicketSummary> {
  const answer = await fetchJson(page, "/api/tickets", { method: "POST", body: { subject, description: `${subject} (test)`, priority } });
  if (answer.status !== 201 || typeof answer.body !== "object" || answer.body === null || !("id" in answer.body) || typeof answer.body.id !== "string") {
    throw new Error(`openTicket: ${String(answer.status)} ${JSON.stringify(answer.body)}`);
  }
  return { id: answer.body.id, subject };
}

/** A write by another person behind the page's back (assign, start...), so the page's copy is stale. */
export async function behindTheScenes(page: Page, path: string, body: unknown): Promise<void> {
  const answer = await fetchJson(page, path, { method: "POST", body });
  if (answer.status !== 200) {
    throw new Error(`${path}: ${String(answer.status)} ${JSON.stringify(answer.body)}`);
  }
}
