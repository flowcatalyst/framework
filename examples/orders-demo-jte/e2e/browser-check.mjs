// Browser check of the JTE orders pages: Datastar actually runs under the page policy, the drawer,
// create dialog and cancel flow work, no CSP violation is reported, and axe finds nothing serious.
//
//   BASE_URL=http://127.0.0.1:<free port> node e2e/browser-check.mjs
//
// The server is started by whoever runs this (see ../README.md in docs/guide/ui-jte.md); Playwright and
// axe come from the ui workspace's installed packages (no new dependency, nothing installed here).
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(path.join(here, '../../../ui/apps/orders/package.json'));
const { chromium } = require('@playwright/test');
const { AxeBuilder } = require('@axe-core/playwright');

const base = process.env.BASE_URL;
if (!base || !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base)) {
  console.error('BASE_URL must be http://127.0.0.1:<port> of the demo started for this check');
  process.exit(2);
}
if (/:(8080|15432)$/.test(base)) {
  console.error('refusing port 8080/15432');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  -- ' + detail : ''}`);
};

const browser = await chromium.launch();

async function newPage(opts = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, ...opts });
  const page = await context.newPage();
  const violations = [];
  const consoleErrors = [];
  const failedRequests = [];
  await page.addInitScript(() => {
    window.__csp = [];
    document.addEventListener('securitypolicyviolation', (e) => window.__csp.push(`${e.violatedDirective} ${e.blockedURI}`));
  });
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('requestfailed', (r) => failedRequests.push(`${r.method()} ${r.url()}`));
  return { context, page, violations, consoleErrors, failedRequests };
}
const csp = (page) => page.evaluate(() => window.__csp);

// What axe finds in the kit's own markup and colours (measured 2026-10-16, Chromium, axe-core 4.13). These
// come from ui-kit's spec and theme, shared with the platform's templ port, not from this module; they
// are a ratchet: a rule not listed, or more nodes than listed, fails. Fixing the kit lowers the numbers.
//   aria-prohibited-attr: the disabled paginator spans carry aria-label without a role (paginator.md)
//   color-contrast: white on --primary (3.35:1), success/info/destructive badges, .fc-page-subtitle, the outline Filters button
const KNOWN_KIT_FINDINGS = { 'aria-prohibited-attr': 2, 'color-contrast': 9 };

async function axe(page, label) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice']).analyze();
  const flat = (v) => `${v.id}[${v.impact}] x${v.nodes.length} ` + v.nodes.slice(0, 12).map((n) => n.target.join(' ') + ' => ' + (n.any[0]?.message ?? n.failureSummary ?? '').replace(/\s+/g, ' ').slice(0, 110)).join(' | ');
  const serious = r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  const unexpected = serious.filter((v) => !(v.id in KNOWN_KIT_FINDINGS) || v.nodes.length > KNOWN_KIT_FINDINGS[v.id]);
  check(`axe (${label}): no serious or critical violation beyond the known kit findings`, unexpected.length === 0, unexpected.map(flat).join('; '));
  const known = serious.filter((v) => !unexpected.includes(v));
  if (known.length) console.log(`      axe (${label}) known kit findings: ` + known.map((v) => `${v.id} x${v.nodes.length}`).join(', '));
  const rest = r.violations.filter((v) => !serious.includes(v));
  if (rest.length) console.log(`      axe (${label}) moderate/minor: ` + rest.map((v) => `${v.id}[${v.impact}] x${v.nodes.length}`).join('; '));
  return r;
}

// 1. The list renders, Datastar runs, nothing is blocked.
{
  const { context, page, consoleErrors, failedRequests } = await newPage();
  await page.goto(`${base}/orders`);
  await page.waitForSelector('tbody#orders-rows tr');
  check('list renders 10 rows', (await page.locator('tbody#orders-rows tr').count()) === 10);
  check('no CSP violation on load', (await csp(page)).length === 0, (await csp(page)).join('; '));
  check('no console error or failed request on load', consoleErrors.length === 0 && failedRequests.length === 0, [...consoleErrors, ...failedRequests].join('; '));
  check('Datastar set the local times', (await page.locator('tbody#orders-rows time').first().textContent()).trim() !== '' && !(await page.locator('tbody#orders-rows time').first().textContent()).includes('UTC'));
  await axe(page, 'list');

  // 2. Drawer: loaded by the server through Datastar, closes with Escape.
  const first = page.locator('tbody#orders-rows td:first-child a').first();
  const name = (await first.textContent()).trim();
  await first.click();
  await page.waitForSelector('aside#order-drawer .fc-drawer-title', { state: 'visible' });
  check('drawer opens with the record loaded by the server', (await page.locator('aside#order-drawer .fc-drawer-title').textContent()).trim() === name);
  check('URL did not change (Datastar, not navigation)', page.url() === `${base}/orders`);
  await axe(page, 'list with drawer open');
  await page.keyboard.press('Escape');
  await page.waitForSelector('aside#order-drawer', { state: 'hidden' });
  check('Escape closes the drawer', true);

  // 3. Create dialog: server validation as patches, then success.
  await page.getByRole('button', { name: 'New order' }).click();
  await page.waitForSelector('dialog#new-order[open]');
  check('create dialog is modal', await page.evaluate(() => document.querySelector('dialog#new-order').matches(':modal')));
  check('autofocus lands on the customer field', await page.evaluate(() => document.activeElement && document.activeElement.id === 'new-order-customer'));
  // the browser's own validation (required) stops an empty submit before the server sees it
  await page.getByRole('button', { name: 'Create order' }).click();
  await page.waitForTimeout(300);
  check('empty submit is stopped by the browser (required)', (await page.locator('#new-order-customer-err').count()) === 0 && (await page.evaluate(() => document.activeElement && document.activeElement.id === 'new-order-customer')));
  await page.locator('#new-order-customer').fill('A');
  await page.locator('#new-order-amount').fill('-3');
  await page.getByRole('button', { name: 'Create order' }).click();
  await page.waitForSelector('#new-order-customer-err');
  check('empty submit shows field errors without reloading', (await page.locator('#new-order-customer[aria-invalid=true]').count()) === 1 && page.url() === `${base}/orders`);
  check('error is linked to the field (aria-describedby)', (await page.locator('#new-order-customer').getAttribute('aria-describedby')) === 'new-order-customer-err');
  await axe(page, 'create dialog with errors');
  await page.locator('#new-order-customer').fill('Linus Torvalds');
  await page.locator('#new-order-amount').fill('1234.50');
  await page.getByRole('button', { name: 'Create order' }).click();
  await page.waitForFunction(() => !document.querySelector('dialog#new-order').open);
  check('success closes the dialog', true);
  await page.waitForSelector('#flash-stack > .fc-flash .fc-flash-summary');
  check('success flash is shown', (await page.locator('#flash-stack > .fc-flash .fc-flash-summary').first().textContent()).includes('Order created.'));
  check('the new order is first in the reloaded list', (await page.locator('tbody#orders-rows td:first-child a').first().textContent()).trim() === 'Linus Torvalds');

  // 4. Cancel with confirmation.
  const row = page.locator('tbody#orders-rows tr').first();
  await row.getByRole('button', { name: /More actions/ }).click();
  await page.getByRole('button', { name: /Cancel order/ }).first().click();
  await page.waitForSelector('dialog#cancel-order[open]');
  await page.locator('dialog#cancel-order button[type=submit]').click();
  await page.waitForFunction(() => document.querySelector('tbody#orders-rows tr td:nth-child(2)').textContent.includes('Cancelled'));
  check('confirmed cancel patches the row to Cancelled', true);
  check('no CSP violation through the whole flow', (await csp(page)).length === 0, (await csp(page)).join('; '));
  check('no console error through the whole flow', consoleErrors.length === 0, consoleErrors.join('; '));
  await context.close();
}

// 5. Dark theme from the cookie, and axe in dark.
{
  const { context, page } = await newPage();
  await context.addCookies([{ name: 'fc_theme', value: 'dark', url: base }]);
  await page.goto(`${base}/orders`);
  check('fc_theme=dark renders html.dark from the server', await page.evaluate(() => document.documentElement.classList.contains('dark')));
  await axe(page, 'list, dark');
  await context.close();
}

// 6. Pages without JavaScript: links and forms work (post/redirect/get).
{
  const { context, page } = await newPage({ javaScriptEnabled: false });
  await page.goto(`${base}/orders?q=grace`);
  check('no-JS: search results render', (await page.locator('tbody#orders-rows tr').count()) > 0);
  await page.goto(`${base}/orders/ord-0001`);
  check('no-JS: detail page renders', (await page.locator('h1.fc-page-title').count()) === 1);
  await context.close();
}

// 7. The policy: Datastar needs 'unsafe-eval'. With it removed, the drawer cannot open (and a violation is reported).
{
  const { context, page, consoleErrors } = await newPage();
  await page.route(`${base}/orders`, async (route) => {
    const res = await route.fetch();
    const headers = { ...res.headers() };
    headers['content-security-policy'] = headers['content-security-policy'].replace(" 'unsafe-eval'", '');
    await route.fulfill({ response: res, headers });
  });
  await page.goto(`${base}/orders`);
  await page.waitForSelector('tbody#orders-rows tr');
  await page.locator('tbody#orders-rows td:first-child a').first().click().catch(() => {});
  await page.waitForTimeout(800);
  const opened = await page.locator('aside#order-drawer .fc-drawer-title').isVisible().catch(() => false);
  const violated = consoleErrors.some((m) => m.includes('unsafe-eval') && m.includes('Content Security Policy'));
  check("without 'unsafe-eval' Datastar cannot run (so the policy needs it)", !opened && violated, `drawerOpened=${opened} evalViolation=${violated}`);
  await context.close();
}

await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
process.exit(failed.length ? 1 : 0);
