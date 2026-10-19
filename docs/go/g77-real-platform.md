# G7.7: the tickets trial against a real platform

Step G7.7 of [`scoping-2026-10-18.md`](scoping-2026-10-18.md) (owner-approved 2026-10-19). Until now the trial (Go
and Java) only talked to a fake platform. This page is the transcript of running the Go service against a **real
local platform** (the Go platform, `fcdev`), then the Java service against a second one for a side by side, with
what each path did and every defect found. Credentials, ids and the secret values are scratch values of a
throw-away platform and are not recorded here.

## The rig

Everything isolated; nothing of the owner's was touched (the owner's `fcdev` on 18080/19090 and the embedded
PostgreSQL on 15432 were running throughout and still are).

| What | Platform 1 (Go run) | Platform 2 (Java run) |
|---|---|---|
| `fcdev` built from `flowcatalyst-go` (`make frontend`, `go build ./cmd/fcdev`) into the session scratchpad | API 28080, metrics 29090, functions 28095 (public port 0), MCP off | API 38080, metrics 39090, functions 38095 |
| Embedded PostgreSQL (own data dir) | 25432 | 35432 |
| The service | `tickets-trial` (Go) on 28091, database `tickets_trial` in that PostgreSQL | `TicketsWeb` (Java 25, classpath from the reactor) on 38091, database `tickets_java` |
| Notification service | a 20-line logging HTTP server on 28099 | on 38099 |
| Outbox | `fcdev outbox` against the service's database, as the application's service account | the same, against `tickets_java` |

Environment overridden for `fcdev`: `FC_API_PORT`, `FC_METRICS_PORT`, `FC_EMBEDDED_DB_PORT`,
`FC_EMBEDDED_DB_PATH`, `FC_FUNCTIONS_PORT`, `FC_FUNCTIONS_PUBLIC_PORT=0`, `FC_MCP_ENABLED=false`,
`FC_OUTBOX_ADMIN_PORT=0`, **`FC_DEV_PID_FILE`** (otherwise two instances share `fcdev.pid`),
**`FC_FN_CLI_CREDENTIALS_PATH`** and `FC_PROFILES_PATH` (scratch files). Every process was stopped by its own PID
(found with `lsof -i :PORT`), `SIGTERM`; no pattern kills. After the run no port of the rig is listening.

Setup of the platform side (through the admin API, authenticated with the dev credential `fcdev start` writes;
scratch client codes `trial-scratch` and `trial-two`, never a real one):

1. `POST /api/applications` `tickets-trial`; `POST /api/clients` twice.
2. `POST /api/service-accounts` (application `tickets-trial`) -> OAuth client id and secret, and the webhook
   signing secret (the receivers' `flowcatalyst.receivers.signing-secret`).
3. `POST /api/applications/{id}/provision-login-client` (PUBLIC, redirect `<service>/auth/callback`) -> the
   service's `auth.client-id`.
4. Users through `POST /api/principals/users` (lead, agent in client one, agent in client two); roles assigned
   **after the first sync** (the roles come from it).

The first sync did not work; steps 5 to 7 are what it took (defects G77-2 and G77-3 below are about exactly this):

5. `PUT /api/service-accounts/{id}/roles` `platform:application-service`.
6. `POST /api/applications/{id}/service-account` (attach the account to the application).
7. `PUT /api/service-accounts/{id}` `{"scope":"ANCHOR","clientIds":[]}`.

## Definition sync (Go)

`tickets-trial sync` as the application's service account, in the order it went:

| Run | Roles | Event types | Connections | Subscriptions | Scheduled jobs |
|---|---|---|---|---|---|
| 1: no role | 403 `PERMISSION_REQUIRED` | 403 | 403 | 403 | 403 |
| 2: role assigned | created 4 | created 6 | 400 `APPLICATION_SERVICE_ACCOUNT_REQUIRED` | 404 `CONNECTION_NOT_FOUND` | 403 `ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP` |
| 3: attached, scope set but `clientIds` kept | updated 4 | 0 | created 1 | created 1 | 403 `ANCHOR_REQUIRED_FOR_PLATFORM_SWEEP` (token `tier` was still `PARTNER`) |
| 4: `clientIds` cleared (token `tier` `ANCHOR`, `clients ["*"]`) | updated 4 | 0 | updated 1 | updated 1 | **created 1** |
| 5: repeated | updated 4 | 0 | updated 1 | updated 1 | 0 |

On the platform afterwards: 4 roles (`tickets-trial:requester|agent|lead|admin` with their permissions), 6 event
types, connection `main` (application-owned, the application's service account), subscription `notify-assignee`
(target `http://localhost:28091/fc/dispatch`, connection `main`, one binding `...:ticket:assigned`) and job
`sla-sweep` (`0 * * * * *`, tracks completion) **with no `targetUrl`** (G77-1). The dry run (`sync --dry-run`) sent
nothing and printed the same codes as the Java service's.

## Sign-in and tickets (Go)

The service's real OIDC code flow against the platform: `GET /auth/login` -> 302 to `/oauth/authorize` -> platform
`POST /auth/login` (email and password) -> 302 back to `/auth/callback` -> session. `GET /auth/session` answered
`tier CLIENT`, `client trial-scratch`, the roles and the permissions mapped from them. Then through the API (with
`Sec-Fetch-Site: same-origin` for the writes): open ticket, assign, comment, transition, SLA policy; the agent of
client two got `TICKET_NOT_FOUND` for client one's ticket and opened their own.

## Outbox delivery (Go)

Opening a ticket wrote two rows to the service's `outbox_messages` (`EVENT` `tickets-trial:support:ticket:opened`,
`AUDIT_LOG` `open-ticket`), `client_id` = the client's code. `fcdev outbox` delivered them within a second; the
rows left the table. On the platform: the event in `msg_events` with `client_id` resolved from the code to the
platform id, `data` as a JSON **object** (the framework writes it as a string inside the CloudEvent; the batch
ingest decodes it), `context_data` `principalId` (the person), and the audit rows in `aud_logs`. The events for
assign, comment, status change and SLA policy arrived the same way. Isolation held: the event for client two's
ticket carries client two's id.

## Dispatch (Go)

Assigning a ticket wrote `ticket:assigned`. The platform matched the subscription, created dispatch job `EVENT`
`tickets-trial:support:ticket:assigned` for client one, and delivered it to `POST /fc/dispatch` of the service:

- **Signed, accepted (200).** An unsigned post answered 401 `MISSING_SIGNATURE`, a wrong signature 401
  `INVALID_SIGNATURE`, an old timestamp 401 `TIMESTAMP_EXPIRED`: the real signature of the connection's service
  account verified.
- **The notify call** reached the notification service: `POST /notifications`, `Authorization: Bearer <that
  client's key>`, `Idempotency-Key` = the dispatch job id, `X-Correlation-Id` = the event's correlation id, body
  `{"to":<assignee>,"ticketId":...,"subject":...,"assignedBy":...}`. Job `COMPLETED`.
- **Failure and retry.** With the notification service stopped the receiver answered 503 (`could not connect; the
  platform retries`), the platform retried (~7 s apart, `attemptCount` 2, status `PENDING` between), and after the
  notification service was back the next retry completed the job. One notification, not two.

## Scheduled job (Go)

- **Before the fix (G77-1)** every firing ended `DELIVERY_FAILED`, `deliveryAttempts 3`, `deliveryError "No target
  URL configured for job"`.
- After the fix (the sync sends `http://localhost:28091/fc/scheduled-jobs`) the firing was accepted (202), the SLA
  sweep ran, and the platform received the completion: `status COMPLETED`, `completionStatus SUCCESS`,
  `completionResult {"flagged":0,"skipped":0}`. With the SLA policy set to one minute for URGENT and a ticket past
  its deadline, the next firing reported `{"flagged":1,"skipped":0}`; the ticket then read `overdue true`,
  `version 2`, and the platform got `ticket:sla-breached` with `context_data` principal = the service principal and
  `executionId` = the job instance (the firing as the cause) and the audit row `flag-sla-breach` by the service
  principal.
- The platform fires about 21 s after the minute (its scheduler polls every 30 s); `scheduledFor` stays the
  minute.

## Java side by side (platform 2)

The same scenario, from a clean platform, with the Java service started from the reactor's classes.

| Path | Go | Java |
|---|---|---|
| Dry run output | 4 roles, 6 event types, connection, subscription, job | identical |
| Sync on a clean platform (with the account set up as above) | created 4, 6, 1, 1, 1 | identical; the repeat run also `updated 4/0/1/1/0` |
| Scheduled job on the platform | no `targetUrl` (before the fix) | **no `targetUrl`: firings `DELIVERY_FAILED "No target URL configured for job"`** (open, G77-1). I set it with `PUT /api/scheduled-jobs/{id}` to continue |
| Sweep after that | `{"flagged":1,"skipped":0}`, `ticket:sla-breached` by the service principal with the job instance as `executionId` | identical |
| Events on the platform (type, source, subject, message group, `data`, `context_data`) | as above | identical field for field (ids differ) |
| Signature checks on `/fc/dispatch` | `MISSING_SIGNATURE`, `TIMESTAMP_EXPIRED` | identical |
| The notify call | `Authorization: Bearer <client key>`, `Idempotency-Key` = job id, `X-Correlation-Id`, same JSON body | identical (only header-name case and `Accept-Encoding` differ) |
| Session and isolation | `tier CLIENT`, client two cannot see client one's ticket | identical |

A dispatch job for a client created less than 5 minutes earlier sat `QUEUED` on platform 2 until the router's
5-minute configuration refresh created that client's queues (G77-4); on platform 1 the refresh had happened
before the first assignment.

## Defects

| # | Defect | Where | Status |
|---|---|---|---|
| G77-1 | A scheduled job is synced with no `targetUrl` unless the service sets one; the platform has no default, so every firing fails `No target URL configured for job`. The Go `ScheduledJob.Targeting` comment said "instead of the platform's default for the application", which does not exist | framework, **Go and Java** | **Go fixed** (`becf624`: the sync sends the service's own scheduled-job receiver for a job with a handler and no target when `subscription-target-base-url` is set; a path target is resolved against the base; 1 new test, 1 changed). **Java open**: same code path (`Definition.ScheduledJob.targetUrl` null), confirmed live; Java needs the same default |
| G77-2 | Nothing told a service what its account needs: `platform:application-service`, attached to the application, anchor reach for the platform-scoped job sync. Three different 403/400 answers on the way | framework docs (shared) | **Documented** (guide `flowcatalyst-module.md`, "What the platform needs before the first sync"). The framework cannot fix the platform's setup |
| G77-3 | Platform: a service account created with `clientIds` has tier `PARTNER`; `PUT {"scope":"ANCHOR"}` is accepted (and returned as `scope ANCHOR`) but the token stays `PARTNER` until `clientIds` is cleared in the same call | platform | **Unfixed** (not edited) |
| G77-4 | Platform: the router's configuration refreshes every 5 minutes, so the dispatch jobs of a client created in the last 5 minutes wait `QUEUED` (queue `<client>-DEFAULT` unknown to the router) | platform | **Unfixed**; documented in the same guide section |
| G77-5 | Platform: syncing unchanged roles, connections and subscriptions reports `updated=N` every run (event types and jobs report 0) | platform | **Unfixed**, cosmetic |
| G77-6 | Platform: a completed dispatch job shows `attemptCount 0` (a job that needed one retry shows 2); audit `entityType` is `Scheduledjobs` for a sync create and `Scheduledjob` for an update | platform | **Unfixed**, cosmetic |

## What this verified, and what it did not

Verified against a real platform, for Go and (marked) Java: definition sync of all five categories as the
application's service account (both); the real OIDC sign-in (both); outbox delivery by `fcdev outbox` of events
and audit rows to the batch ingest, with client mapping (both); a dispatch job created from a subscription,
delivered to the receiver with a real signature, the per-client notify call, retry after a 503 (Go; Java the
success path); the scheduled-job firing reaching the SLA sweep receiver and the completion report (both); the
flagged ticket and its event with the service principal as the actor (both).

**Not verified:** a second replica (the overlap lock and the leader-gated loops have only their unit and
PostgreSQL tests); the Go service on a database the Java one wrote; the notification service refusing with a 4xx
(`Reject`) and a client without a key on a real platform; a subscription whose connection is a different service
account than the application's (both used one account, so "the connection's account signs" was exercised only in
the case where both are the same); the browser SPA against the real platform (the 24 e2e tests use the fake);
other browsers; the `sync-on-boot` setting; long soak.
