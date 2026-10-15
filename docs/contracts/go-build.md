# Contract: the Go build

Status: G0 (2026-10-14). The Go counterpart of [`build.md`](build.md) and
[`rust-build.md`](rust-build.md). Design: [`../go/design.md`](../go/design.md) section 2.

## 1. Where it lives

The `go/` directory of this repository. It is neither a Maven module nor a Cargo crate; the Java
and Rust builds ignore it. It shares with them the contracts and the conformance vectors in
`conformance/` at the repository root, which the Java tests write and the Go tests only compare.

## 2. Modules

| Module | Directory | May import |
|---|---|---|
| `github.com/flowcatalyst/framework/go` | `go/` | the standard library, `golang.org/x/sync`, pgx v5, go-redis v9, `go.yaml.in/yaml/v3`; embedded-postgres in `fwtest` only; `golang.org/x/tools` in `cmd/fwvet` only |
| `github.com/flowcatalyst/framework/go/flowcatalyst` | `go/flowcatalyst/` | the core module and the platform SDK (`pkg/fcsdk`); the only module that imports fcsdk |
| `github.com/flowcatalyst/framework/go/tools` | `go/tools/` | the pinned tools (`tool` directives): golangci-lint, NilAway, gofumpt; never imported |

- **How `go/flowcatalyst` resolves fcsdk (G4, 2026-10-15; released 2026-10-15).** fcsdk is the module
  `github.com/flowcatalyst/flowcatalyst/pkg/fcsdk` in the platform repository, released as the tag
  `pkg/fcsdk/v0.0.2`; `go/flowcatalyst/go.mod` requires that version, with no replace, and the checks
  and CI resolve it from the module proxy (no platform checkout needed). To develop against unreleased
  fcsdk, set `FCSDK=<path to pkg/fcsdk>` (for example `make -C go go-check FCSDK=../flowcatalyst-go/pkg/fcsdk`):
  `make -C go fc-modfile` then writes `go/flowcatalyst/go.local.work` (not committed; a `replace` of
  fcsdk by that checkout) and every flowcatalyst target runs with `GOWORK` set to it. Never commit a
  `replace`. `go.mod` also has `replace github.com/flowcatalyst/framework/go => ../`, which only
  applies inside this repository (a replace in a dependency is ignored by its consumers, who need both
  modules tagged). `make -C go fc-tidy` runs `go mod tidy` against the released fcsdk; bump the version
  in `go.mod` first. `go/flowcatalyst/migrations/` holds a shared file (`shared-files.txt`).
- A driver is a package (`locks/redislock`), compiled only when an application imports it, so there
  is no build tag, feature or BOM: the module graph may hold a dependency a binary never compiles.
- Module paths are working names until the owner settles naming (design decision 33). Nothing is
  published; tags would be `go/vX.Y.Z` and `go/flowcatalyst/vX.Y.Z`.
- Licence: Apache-2.0 (`go/LICENSE`, `go/flowcatalyst/LICENSE`); dependencies permissive only.

## 3. Toolchain

- `go 1.27` in every `go.mod`; CI and development use Go 1.27.2. The directive changes deliberately,
  with the platform, in one commit.
- Tools are pinned in `go/tools/go.mod` and built by the local toolchain (golangci-lint releases
  built with an older Go cannot load Go 1.27 export data). NilAway has no releases; it is pinned by
  pseudo-version there. gofumpt runs with `go tool -modfile=tools/go.mod gofumpt`; golangci-lint and
  NilAway are built into `go/bin/` (`make -C go tools`: `go build -modfile=tools/go.mod`), because the
  flowcatalyst module is checked in a workspace, where `go tool -modfile` is refused, and the two
  fail under `GOFLAGS=-modfile`.

## 4. Lints and formatting

`make -C go go-check` is what CI runs and what a commit needs: `fmt-check` (gofumpt), `lint`
(golangci-lint with `go/.golangci.yml`: the platform's profile plus `gochecksumtype`, `forbidigo` and
revive's `exported`), `vet` (`go vet` with the framework's analyzers, `cmd/fwvet`), `nilaway` and
`test`; each runs on the core module and then on `go/flowcatalyst`.

- `forbidigo` keeps library packages free of global state: no `time.Now`/`time.Since` (time comes
  from `fw.Clock`), no `os.Getenv` outside `config`, no `slog.Default`, no `context.Background`
  outside `main` and tests, no `reflect` outside the JSON edge. Exceptions are a `//nolint` with the
  reason at the one site.
- `fwvet` checks what Go cannot check at compile time: the literal arguments of `Must...` functions
  (each one's `Parse...` runs at lint time), later route patterns and sum-type literals.
- Expected outcomes with data are sealed interfaces marked `//sumtype:decl`; `exhaustive` and
  `gochecksumtype` run with `default-signifies-exhaustive: false`.

## 5. Tests

- `go test -race ./...` in both modules. **No build tags** that hide tests: a test that needs
  PostgreSQL or Redis always runs and fails, with instructions, when the server cannot be had.
- PostgreSQL: `fwtest` starts one embedded PostgreSQL 18 per test binary on a free port and gives
  each test a database cloned from a template prepared once; `FW_TEST_DATABASE_URL` points it at
  another server (CI's service container). Redis: `redis-server` or `valkey-server` from `PATH`, or
  `FW_TEST_REDIS_URL`. No test connects to port 15432 or binds 8080.
- Time comes from `fwtest.Clock`; code that waits is tested with short real leases or
  `testing/synctest`.

## 6. Shared files

`//go:embed` cannot reach outside the module, so a statement, script or migration the Go code sends
and that the Java framework sends too is **copied** into the Go package that embeds it.
`go/internal/conformance/shared-files.txt` lists each source and copy; `make -C go go-sync-shared`
refreshes them; a conformance test fails when a copy differs from its source by a byte.
Placeholders: the shared statements use JDBC `?`; Go numbers them `$1`, `$2`, ... once, when the
package initialises.

## 7. CI

`.github/workflows/go.yml`, run when `go/`, `conformance/` or the workflow change: the lint job
(`fmt-check`, `lint`, `vet`, `nilaway`), the test job (PostgreSQL 18 and Redis as service
containers, passed to `fwtest` through `FW_TEST_DATABASE_URL` and `FW_TEST_REDIS_URL`) and
`govulncheck`. Written in G0; not run until the owner allows it.
