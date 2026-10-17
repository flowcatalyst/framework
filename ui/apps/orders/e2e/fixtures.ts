// The orders application's end-to-end helpers on the shared kit (@flowcatalyst/ui/e2e, docs/guide/e2e-kit.md):
// the kit provides the `test` with the CSP monitor, the control server's client, `openPage` and `fetchJson`;
// this file adds the demo's roles and its orders helpers. The Java side is OrdersE2e in the demo's tests.
import { control, expect, fetchJson, openPage, signInAs as signIn, test, type JsonAnswer } from "@flowcatalyst/ui/e2e";
import type { Page } from "@playwright/test";

export { control, expect, fetchJson, openPage, test, type JsonAnswer };

/** The roles of the demo (OrdersWeb): who may read, and who may also change. */
export const CLERK = "orders-demo:clerk";
export const VIEWER = "orders-demo:viewer";
/** The administrator of the demo's admin area (M4d). */
export const ADMIN = "orders-demo:admin";

/** The next sign-in is a user holding these roles (none: a signed-in user with no permission). */
export async function signInAs(...roles: string[]): Promise<void> {
  await signIn({ roles });
}

/** Empties the orders and makes the next sign-in a clerk. */
export async function freshData(): Promise<void> {
  await control("/control/reset");
  await control("/control/platform-reads?value=");
  await signInAs(CLERK);
}

export interface OrderSummary {
  readonly id: string;
  readonly customer: string;
}

/** Places an order as the signed-in page does (same-origin fetch: the CSRF rule needs the browser). */
export async function placeOrder(page: Page, customer: string, lines: readonly { sku: string; quantity: number; unitPriceCents: number }[] = []): Promise<OrderSummary> {
  return page.evaluate(
    async ({ customer: who, lines: wanted }) => {
      const post = async (path: string, body: unknown): Promise<{ id: string; customer: string }> => {
        const response = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
        if (!response.ok) {
          throw new Error(`${path}: ${String(response.status)}`);
        }
        const json: unknown = await response.json();
        if (typeof json === "object" && json !== null && "id" in json && "customer" in json && typeof json.id === "string" && typeof json.customer === "string") {
          return { id: json.id, customer: json.customer };
        }
        throw new Error("unexpected answer");
      };
      const placed = await post("/api/orders", { customer: who });
      for (const line of wanted) {
        await post(`/api/orders/${placed.id}/lines`, line);
      }
      return placed;
    },
    { customer, lines },
  );
}

/** Cancels an order behind the page's back (another user), so the page's copy is stale. */
export async function cancelBehindTheScenes(page: Page, id: string): Promise<void> {
  await page.evaluate(async (orderId) => {
    const response = await fetch(`/api/orders/${orderId}/cancel`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ reason: "someone else" }) });
    if (!response.ok) {
      throw new Error(`cancel: ${String(response.status)}`);
    }
  }, id);
}
