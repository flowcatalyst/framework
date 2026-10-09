# Working rules for this repository

Java 25, Maven multi-module (`framework-*`, `examples/*`). Application framework for business
services next to the FlowCatalyst platform. Read `docs/guide/STATUS.md` for what exists.

## Documentation is part of the change

- Every change to public API or behaviour updates the matching page in `docs/guide/` and
  `docs/guide/STATUS.md` **in the same commit**, then runs `docs/guide/verify.sh` (it must print OK).
- Each guide page starts with `<!-- types: A, B -->` naming the public types it documents; the
  script fails if a name is no longer declared under `framework-*/src`. Add the types you add.
- After verifying, set `Last verified against commit <hash>` in STATUS.md to
  `git rev-parse --short HEAD` (a commit that is already an ancestor of the one you are making).
- Guide snippets must come from the examples, the tests or compiled code. Do not invent APIs. If a
  contract and the code disagree, say so in STATUS.md ("Where the guide and the contracts can
  differ"); do not silently change either.
- No Liquid markers (`{{`, `{%`) in guide pages; Jekyll would evaluate them.

## Contracts first

Contracts live in `docs/contracts/` and are written before the code; the code is a port of the
text. Amend a contract when the build finds it wrong, in the same commit as the fix.
Plan and rulings: `docs/implementation-plan.md`, `docs/framework-plumbing.md`.

## Build and test

- `./mvnw -q verify` runs everything and needs Docker (Testcontainers PostgreSQL 18 and Redis; the
  `orders-demo` checked-SQL `check` goal also starts a container).
- Count tests from the reports, never from memory:
  `find . -path '*/surefire-reports/TEST-*.xml' -exec grep -ho '<testsuite [^>]*' {} + | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ - | bc`
- Error Prone with NullAway is an error in main code; every package is `@NullMarked`. The JVM flags
  Error Prone needs on JDK 25 are in `.mvn/jvm.config`; keep them.
- House rules: values are records; expected outcomes are sealed result types; no reflection or
  scanning for wiring; time from an injected `InstantSource`; SLF4J API only in libraries.
- Docs-only changes need no build; run `docs/guide/verify.sh`.

## Git

- Stage explicit paths (`git add path ...`). Never `git commit -a`, never `git add -A`/`.`.
- Never chain a commit after a build or test command. Run the build alone, read the result, then
  commit as a separate step.
- One focused commit per step. End every commit message with the trailer of the model that did the
  work, for example `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Do not push unless asked. Do not enable GitHub Pages.

## Secrets

No secrets in the repository: no passwords, tokens or client secrets in code, config, tests or
docs. Config values for secrets come from the environment; `config/secrets.local.yaml` is git-ignored
and read only when `APP_ENV` is `local` or `test`.
