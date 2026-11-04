# Plan: internationalisation (languages and locale formatting)

Status: ruled 2026-11-03 for the framework; the contract is [`contracts/i18n.md`](contracts/i18n.md),
which wins where the two differ. Owner rulings on section 7: (1) build the mechanism; English and
Afrikaans (the owner reads it) as the showcase; further languages follow the market, likely not
South Africa; (5) flat JSON with named placeholders and CLDR plural categories, no ICU
MessageFormat; (2) translated catalogs for driver and yard screens (field-worker, portals), the
admin console stays English (2026-11-03); (4) API error messages stay English (2026-11-03).
Still open: (3) the SPA (the dogfood plan retires it). The platform steps (section 5) come after
the framework.
Covers the framework (Java, Go, the Nuxt UI) and the FlowCatalyst platform, which uses the
framework's Go UI kit. English stays the default and the fallback for every text.

## 1. What exists today

Nothing. No message catalogs, no locale resolution, no Accept-Language handling, no language
field anywhere in either data model. Every label, validation message, flash, email and error
message is a literal English string. The one locale-aware behaviour is dates (and in the SPA
numbers) rendered in the browser's default locale.

| Where | Today |
|---|---|
| Java kit (JTE, `framework-ui`) | 89 templates, about 26 with literal English ("Cancel", "Discard Changes", "Previous"); `document.jte` has `lang="en"` |
| Go kit (`go/ui/kit`) | 26 templ files, 14 with about 53 English lines (`list.templ` most); some components take a label prop with an English default |
| Validation (Java `Rules`, Go `validate`) | stable codes (`NOT_NULL`, `TOO_SHORT`) with an English message beside each |
| Flashes (`go/ui/views`) | key → one fixed message; no locale in the lookup |
| Dates | server renders ISO UTC; `kit.LocalTime` / `local_time.jte` re-render in the browser locale |
| Nuxt UI (`ui/`) | no vue-i18n; `UApp` without `:locale`; `time.ts` takes an optional locale |
| Platform SPA (`frontend/`) | no vue-i18n, PrimeVue without `locale`; about 100 of 118 `.vue` files with literal English; 39 files with their own `formatDate` |
| Platform console (`internal/ui`) | 55 of 56 templ files with literal English (about 980 lines); dates as fixed UTC strings or `kit.LocalTime` |
| Platform emails | hard-coded English subjects and bodies (password reset, invitations, security notices) |
| Login theme | `BrandName`, `BrandSubtitle`, `FooterText`: one string each |
| API errors (both) | `{error: CODE, message: English}` |
| Data model | no `locale` on principals or clients; no OIDC `ui_locales` / `locale` claim |

The good news is the codes. Validation results, API errors and flashes already carry a stable code
next to the English text, so translating them is a lookup by code, not a rewrite.

## 2. Principles

- **The code is the contract; the text is presentation.** APIs keep returning the code and an
  English `message` (logs, SDKs and other services read it). User interfaces translate by code.
- **One locale per request**, decided once and carried in the request context, never re-derived.
- **English is the source.** Catalogs are keyed by stable message keys; English is the catalog every
  other one falls back to, key by key. A missing translation shows English, never a key.
- **Formatting is not translation.** Dates, numbers and money follow the locale (en-ZA writes
  "R 3 132,48"); the text follows the language. Both come from the one locale.
- **No heavy machinery.** Placeholders by name (`{min}`) and plural categories (one/other, and the
  CLDR categories a language needs). No full ICU MessageFormat unless a language requires it.

## 3. Locale resolution (both frameworks, the same order)

1. The signed-in user's preference (a `locale` on the principal), if set.
2. The client's default (a `defaultLocale` on the client/tenant), if set.
3. The request's `Accept-Language`, matched against the languages the application ships.
4. English.

A BCP 47 tag (`en`, `en-ZA`, `af`, `pt-MZ`). The resolved locale goes in the request context in
Java and Go, and the document's `<html lang>` comes from it.

## 4. The framework

### Catalogs

- One catalog per language, a flat JSON file of `key → message` (`kit.pagination.next`,
  `validation.TOO_SHORT`, `flash.<app key>`).
- **The kit catalog is shared by the Java and Go kits.** Both kits render the same components with
  the same words (ui-kit contract), so they get one source file in `ui-kit/messages/<lang>.json`,
  embedded by both, the way the conformance vectors are shared.
- An application adds its own catalog for its own pages, layered over the kit's.
- Go: `go/ui/i18n` (lookup, placeholders, plural rules for the shipped languages), standard
  library only. Java: the same in `framework-ui`, no `ResourceBundle` (its properties-file
  encoding and fallback rules differ from what Go would do).
- Nuxt (`ui/`): vue-i18n, loading the same JSON files; `UApp :locale` from the same locale.

### What changes in the kits

- Every literal English string in the Java and Go kits becomes a catalog key with the English text
  as its value. The output is identical in English, which the existing kit tests prove.
- Components that take a label prop keep it; the default comes from the catalog.
- Validation and flash rendering look the message up by code in the request's locale.
- `kit.LocalTime` keeps working as it is; two siblings join it, `kit.LocalNumber` and
  `kit.LocalMoney`. The server writes the plain value plus the locale, and the browser formats it
  with `Intl.NumberFormat`. So the server needs no locale formatting data at all (no
  `golang.org/x/text`), and money from docs/money-and-units-plan.md shows correctly.

### Checks

- A test that every key in a non-English catalog exists in English, and a report (not a failure)
  of the English keys a catalog has not translated yet.
- `verify.sh` refuses a literal user-visible string in a kit template once the extraction is done.

## 5. The platform

In order, each step useful on its own:

1. **Data model**: nullable `locale` on `iam_principals`, nullable `default_locale` on
   `tnt_clients`, set from the user's profile and the client's settings. OIDC: accept `ui_locales`
   on `/oauth/authorize` (the login page uses it) and put the `locale` claim in the id_token.
2. **Server-rendered console**: adopts the framework kit catalog for free once section 4 lands;
   then its own pages' strings (about 980 lines in 55 files) move to a platform catalog. That is the
   bulk of the work and is mechanical.
3. **SPA**: vue-i18n plus PrimeVue's `locale` option; one shared `formatDate` / `formatNumber`
   instead of 39 local copies, all taking the resolved locale. Then the strings of about 100 `.vue`
   files. If the SPA is being retired in favour of the server-rendered console, skip this step.
4. **Login theme**: `BrandName`, `BrandSubtitle`, `FooterText` become per-language maps with an
   English entry; the existing single string is read as English.
5. **Emails**: subjects and bodies from the catalog, in the recipient's locale (principal, then
   client, then English).
6. API error messages stay English (section 2).

## 6. Effort, roughly

- Framework mechanism (catalog, resolution, shared kit catalog, both kits extracted, LocalNumber /
  LocalMoney, checks): the substantial part, done once.
- Platform data model and OIDC: small.
- Platform console strings: large but mechanical.
- SPA: large; worth doing only if the SPA stays.
- Each new language afterwards: translation work only, no code.

## 7. Rulings needed

1. **Which languages first, and for whom?** The mechanism is the same for one extra language or
   ten; the translation work is not. (For southern African logistics: Afrikaans, Zulu, Portuguese
   for Mozambique? Or none yet, the mechanism only?)
2. **Which surfaces need other languages?** Driver/yard-facing screens (field-worker, portals) are
   likely; the platform's admin console perhaps not. Proposed: the mechanism everywhere, translated
   catalogs only where users need them.
3. **The SPA**: translate it, or only the server-rendered console?
4. **API messages** stay English (proposed), or follow `Accept-Language` too?
5. **Catalog format**: flat JSON with named placeholders and plural categories (proposed), or ICU
   MessageFormat?
