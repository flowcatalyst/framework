# Handover (2026-10-09)

The plan and status live in `implementation-plan.md` and `guide/STATUS.md`; contracts in
`contracts/`; developer docs in `guide/` (keep them current: see `../CLAUDE.md`). This note is
rewritten at hand-off and can lag the code in between.

- Done: M1, M2a, M2b, M3, core hooks (`started`, `find`, `Command`/`launch`), M4a (client, sync
  command, `outbox` backlog health check), M4b (dispatch and scheduled-job receivers, per-receiver
  secrets, `DispatchDelivery.once` with retention, lock opt-out, `flowcatalyst.receivers.enabled`),
  a start-up check for two config keys that read one environment variable.
- Review by the first application (2026-10-09): 28 findings on the guide, all handled: pages fixed,
  gaps turned into MA items 7 to 9 in the plan, three small code changes (env-variable collision
  check, receivers 404 / `enabled`, anchor check in `verify.sh`).
- Next: item MA (BOM/publishing, test overrides, background + metrics, dispatch-job outbox rows,
  secret resolver, HTTP client, informational health checks, the `framework-http` dependency
  split, rate limiting), then contracts for M-UI (JTE) and M4c (sessions and OIDC login), then
  the `/admin` area. Platform work for scoped event/dispatch-job reads is listed in
  `contracts/flowcatalyst.md` section 5.
- Rulings: the platform delivers the outbox; handler failure/timeout in a receiver is a
  transient error; a receiver without handlers answers 404 (reverses "routes stay and refuse");
  sessions have cookie/database/redis drivers; Opus for security-sensitive slices, Sonnet for
  wiring.
