# Plan: porting the FlowCatalyst UI to the framework UI (dogfooding)

Status: approved in direction 2026-10-09 (owner: move off PrimeVue completely; build the Go templ
version; keep the Go platform). Phase 0 starts in the Go repository. Rulings are in section 8.

## 1. Goal

Re-implement the FlowCatalyst platform's admin UI on the framework's UI kit (HTML + Tailwind +
Datastar, server-rendered) so that we use our own component set on a real, large application,
find what is wrong or missing in it, and fix the kit. Two template implementations of the same
kit are needed: **templ** (Go) and **JTE** (Java).

## 2. What we are porting from

The Go platform's SPA (`flowcatalyst-go/frontend`): Vue 3 + PrimeVue 4, Pinia, vee-validate + zod,
an API client generated from the OpenAPI lock file.

| Fact | Number |
|---|---|
| Vue single-file components | 109, about 41,000 lines |
| Page areas | 19 (largest: authentication 5.7k lines, users 3.5k, platform 2.8k, service accounts 2.5k, auth 2.4k, event types 2.0k, developer 2.1k, authorization 2.0k) |
| Most-used PrimeVue components | Button 427, Tag 139, InputText 138, Select 75, Message 71, DataTable 51, Dialog 49, Textarea 32, MultiSelect 28, InputNumber 26, Checkbox 22, Chip 17, ToggleSwitch 11, Password 10, AutoComplete 8 |
| Rarely used | Tabs, Popover, DatePicker, SelectButton, Drawer, ConfirmDialog (1 to 3 each) |
| Not plain HTML | WebAuthn passkeys (`@simplewebauthn/browser`), Swagger UI, Mermaid diagrams, Markdown docs (`marked` + DOMPurify), 2FA setup |

The UI kit already has 53 component specs and a browser-checked gallery. Gaps against the SPA's
needs, to be confirmed by the pilot: AutoComplete, MultiSelect with search, InputNumber, a
password field with strength meter, a multi-step form, file import dialog, the notification banner
stack, the permission-denied modal, a client switcher.

## 3. Two dogfood targets, one screen design

| Target | Template language | What it replaces | Data source |
|---|---|---|---|
| **A. The Go platform UI** | templ | the Vue SPA, area by area | repositories and use cases directly (no HTTP hop) |
| **B. The framework `/admin` area (M4d/M4e)** | JTE | nothing (new) | the platform API through `PlatformClient` |

The framework's admin pages (users, event types, config, events, dispatch jobs) are the same
screens as the platform's, scoped to one application. So the screens are designed once and built
twice. A screen that is specified for target A gives target B its markup for free, and each
target proves the kit against a different kind of data layer.

## 4. Keeping two template sets from drifting

The kit's Markdown specs stay the authority. Add what is missing to make them testable:

1. **Props table per component** in each spec (names, types, defaults, slots), language-neutral
   and camelCase, so `Button(variant, size, disabled, icon…)` has one signature everywhere.
2. **Fixtures:** `ui-kit/fixtures/<component>/<case>.json` (props) with a golden
   `<case>.html`. The gallery's markup is the first source of the goldens.
3. **Conformance tests in both implementations:** each renders every fixture and compares to the
   golden after whitespace and attribute-order normalisation. A new component or variant is not
   done until its fixture exists and both implementations pass.
4. **A shared stylesheet** (`kit.css` built once by the kit's script) served by both.
5. **A screenshot check** of the gallery against both implementations' rendering of the same
   fixtures, run by hand at first.

The kit moves out of the framework repository into its own (`flowcatalyst/ui-kit`), consumed by
the framework (`framework-ui`, JTE) and by the Go repository (`internal/ui`, templ). A subtree or
submodule is the stop-gap if extraction waits.

## 5. Architecture notes per side

**Go (templ).**
- templ components as plain Go functions; a `ui` package mirrors the kit. templ is a code
  generator (generated `_templ.go` committed, as sqlc output is); the runtime is small.
- A small Datastar adapter written from the 1.0 SDK spec, as the Java one was (events
  `datastar-patch-elements` and `datastar-patch-signals`; signals read from the query or body).
  The official Go SDK is the alternative; the repository prefers standard Go.
- Handlers render with the existing session (the `fc_session` cookie) and the existing
  permission checks; pages sit under `/ui/...` next to the SPA and are switched over per area.
- Forms: server-side validation returns field errors as an element patch; the dirty-form pattern
  uses the kit's Datastar signals. This replaces vee-validate and zod.
- Lists: cursor pagination and filters as query parameters, so every filtered view has a URL
  (which also gives "requeue everything matching this view" something stable to act on).

**Java (JTE).** As planned for M-UI: precompiled templates, a `Response.view(...)` helper, and
page and fragment rendering from one template. The admin pages call `PlatformClient`; a user's
permissions are enforced in the framework before the call (the platform treats the app's own
service account as the caller).

**Islands.** Anything that needs real client code stays a small vendored script and is rendered
by a component with a documented contract: WebAuthn, Mermaid, Swagger UI. The kit's rule of "no
JavaScript files" gets a stated exception list.

## 6. Phases

Each phase ends with something working and a list of kit defects found, recorded in
`ui-kit/PORTING-NOTES.md` and fixed before the next phase starts.

| Phase | Work | Exit criteria |
|---|---|---|
| 0. Foundations | Props tables, fixtures and goldens for the components the pilot needs; the conformance harness in Go and Java; extract the kit repository; Datastar adapter for Go | A button, field, table and dialog render identically in templ and JTE (fixtures green) |
| 1. Pilot, Go | App shell and navigation; **dispatch pools** and **connections** (small CRUD: list, filter, create and edit dialog, delete confirm, drawer) in templ under `/ui` | Both pages usable for real work beside the SPA; the CRUD page pattern captured as composite specs (page header, filter bar, data table, row actions, form dialog) |
| 2. Pilot, Java | The same two screens as the first `/admin` pages in JTE, over `PlatformClient` | Same fixtures, same markup; differences recorded |
| 3. Core operations, Go | event types, subscriptions, applications, clients, scheduled jobs, processes, **dispatch jobs and events** (the heavy list pages; filter-driven bulk actions) | These areas switched to the new UI by default; SPA routes redirect |
| 4. Identity, Go | users, roles and permissions, service accounts, identity providers, OAuth clients, portal users | Same |
| 5. Hard areas | login and 2FA, passkeys, documentation viewer, functions, developer area, platform settings | SPA no longer needed except as a fallback |
| 6. Retire the SPA | Remove Vue, PrimeVue, the generated TS client and the frontend build from the Go repository | One binary, no Node in the build |
| In parallel (Java) | Admin slices in the order users, event types, config, events and dispatch jobs, as the framework's M4e | The framework ships a working admin built from the same kit |

## 6a. The port lives under `/ui` until the owner switches it over

The new UI is served on its own path prefix, `/ui`, beside the existing SPA, which stays the
default. It is dogfooded there until the owner is completely happy. No SPA route redirects to it,
and no SPA code is removed, without the owner's explicit approval, area by area. The exit
criteria in section 6 that say "switched" or "retired" mean: ready to be switched, pending that
approval.

## 7. Risks and how they are handled

- **Two template sets cost twice.** Fixtures and conformance tests are the control; only
  components the Java admin needs are built in JTE at first.
- **Server-rendered UI is a different programming model from the SPA.** Rich interactions
  (multi-select with search, drag, inline edit) need the kit to grow; the pilot is chosen to
  find the first of these cheaply.
- **Large screens may be slow to render or patch.** Dispatch jobs and events are the stress
  cases; measure them in phase 3, not at the end.
- **Auth and security pages are the riskiest to port.** They go last, with the SPA as fallback.
- **The SPA keeps changing while we port.** New features go into the SPA until the area is
  switched; the port picks them up area by area (for example requeue-by-filter built in the SPA
  this week).
- **Accessibility and browser support.** The kit already has an accessibility checklist; the
  port must not regress it. Dialogs use Datastar rather than `commandfor` for browser support.

## 8. Rulings and open points

Rulings (2026-10-09):
- The Go platform stays (the Java platform port is frozen). Replacing PrimeVue is the reason for
  the port, so the Vue SPA is replaced **completely** in phases (section 6), not only for new areas.
- Datastar for Go: the **official SDK**, `github.com/starfederation/datastar-go` (v1.2.2, MIT;
  direct dependencies include a compression library, `klauspost/compress`, `bytebufferpool` and
  a JSON-schema validator). It is wrapped by a thin `ui/ds` package of ours, so the dependency
  stays contained and can be dropped later.
- The Java framework `/admin` (JTE) remains the second target; templ comes first because it
  replaces a working product.

Defaults being used until the owner says otherwise:
- The kit stays in the framework repository for now; the Go repository vendors the built
  stylesheet, the icon sprite and a pinned `datastar.js` (no CDN at run time), with a sync script.
  Extracting the kit into its own repository is a later step.
- Pilot pages: dispatch pools and connections.
- The first Java admin pages do not wait for M4c login.
