# Handover (2026-10-09)

The plan and status live in `implementation-plan.md` and `guide/STATUS.md`; contracts in
`contracts/`; developer docs in `guide/` (keep them current: see `../CLAUDE.md`).

- Done: M1, M2a, M2b, M3, core hooks (`started`, `find`, `Command`/`launch`), M4a slices 1-2.
- In progress: M4b receivers and the `outbox` backlog health check (Opus agent).
- Next: item MA (BOM/publishing, test overrides, background + metrics, dispatch-job outbox rows,
  secret resolver), then contracts for M-UI (JTE) and M4c (sessions and OIDC login), then the
  `/admin` area. Platform work for scoped event/dispatch-job reads is listed in
  `contracts/flowcatalyst.md` section 5.
- Rulings: the platform delivers the outbox; handler failure/timeout in a receiver is a
  transient error; sessions have cookie/database/redis drivers; Opus for security-sensitive
  slices, Sonnet for wiring.
