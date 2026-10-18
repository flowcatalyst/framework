<!-- types: Ids, Context, Clock, Database, Redis -->
# The Go framework

A further implementation of the [contracts](../reference/contracts.md), in Go, in the `go/`
directory of this repository. Applications built on it are meant to be interchangeable with Java
ones to the FlowCatalyst platform: the same outbox rows, ids, config semantics and framework
tables, so a Go service can take over a Java service's database. The design is in
[`docs/go/`](../../go/design.md); the plan and its state in [Go milestones](../../go/milestones.md).
The Java pages of this guide describe the Java framework only.

**State:** G0 (the module skeleton, toolchain, test fixtures and the conformance runner) and G1
(foundation and locks: [application and config](core.md), [database and Redis](database.md),
[locks](locks.md)) and G2 (the [write path](write-path.md) and [checked SQL](checked-sql.md)) and G3
([HTTP and the SPA](http.md), [typed operations and OpenAPI](openapi.md)) and G4 (the platform:
[rate limiting](rate-limiting.md), the [HTTP client](http-client.md), [secrets](secrets.md) and the
[FlowCatalyst module](flowcatalyst.md)) are built, and so are the load phase and the transaction join of the write path and [per-client settings](client-settings.md) (G7.3 to G7.5); G5b ([sessions and login](sessions-and-login.md)) is in progress.
Nothing is published; module and package names are the design's working names.

## The tree

```
go/
  go.mod                 module github.com/flowcatalyst/framework/go, go 1.27
  .golangci.yml          the platform's lint profile plus gochecksumtype, forbidigo, revive exported
  Makefile               go-check (fmt-check, lint, vet, nilaway, test), go-sync-shared
  fw/                    the core: Context and its ids, Clock, TSIDs (Ids)
  httpfw/                HTTP: routes, errors, server, health; spa/, openapi/, validate/, sessionapi/
  auth/                  sessions and login (in progress): relying party, platform tokens; authtest/ the test provider
  ratelimit/             rate limiting: policy, memory limiter, middleware, keys; redisrl/ the Redis limiter
  clientsettings/        per-client settings: definitions in code, fw_client_settings, repository, store
  httpclient/            the HTTP client library; module/ the framework adapter (named clients, metrics)
  secrets/               per-client secrets: Env and File stores, cache policy, resolver
  fwtest/                PostgreSQL 18 and Redis for tests, a database per test, a fake clock
  internal/conformance/  the conformance runner: checks the shared vectors in ../conformance/
  cmd/fwvet/             the framework's analyzers, run by go vet
  flowcatalyst/          module .../go/flowcatalyst, the only fcsdk importer: client, definitions and
                         sync, health checks, receivers, Once; examples/receivers-demo
  tools/                 module pinning golangci-lint, NilAway and gofumpt (go tool)
```

The build rules (modules, toolchain, lints, tests, shared files, CI) are the
[Go build contract](../../contracts/go-build.md).

## Building and testing

From the repository root:

```
make -C go go-check     # fmt-check, lint, vet (with fwvet), nilaway, go test -race
make -C go test         # only the tests
make -C go fmt          # gofumpt the sources
```

The tools run through `go tool -modfile=tools/go.mod`, so they are built by the local Go 1.27
toolchain: golangci-lint v2.14.0, NilAway (pinned by pseudo-version, it has no releases) and
gofumpt.

## Tests with real infrastructure

A test package that needs PostgreSQL or Redis declares a `TestMain`, optionally with a function
that prepares the template database once (from `go/fwtest/fwtest_test.go`):

```go
func TestMain(m *testing.M) {
	Main(m, Template(func(ctx context.Context, u string) error {
		conn, err := pgx.Connect(ctx, u)
		if err != nil {
			return err
		}
		defer conn.Close(ctx)
		_, err = conn.Exec(ctx, "CREATE TABLE from_template (n int); INSERT INTO from_template VALUES (42)")
		return err
	}))
}
```

Each test then asks for its own database, cloned from the template, and dropped when it ends;
tests may run in parallel:

```go
a, b := NewDatabase(t), NewDatabase(t)
```

- `fwtest.NewDatabase` returns a `Database` with its name and a `postgres://` URL. The first call
  starts one embedded PostgreSQL 18 per test binary on a free port, in a temporary directory removed
  at the end. With `FW_TEST_DATABASE_URL` set (a role that may `CREATE DATABASE`), that server is
  used instead; CI sets it to its service container.
- `fwtest.NewRedis` returns a `Redis` with the server's URL and a key prefix of the test's own. The
  server is `redis-server` (or `valkey-server`) from `PATH`, started once per test binary, or the
  one at `FW_TEST_REDIS_URL`. Without either, the test **fails** with the instruction to install
  one: Redis tests need real Lua semantics and are never skipped.
- No test connects to port 15432 (the platform's development database) or binds 8080:
  `fwtest.FreePort` skips both, and a `FW_TEST_DATABASE_URL` on 15432 is refused.
- `fwtest.NewClock` is a fake `fw.Clock` that tests set and move.

There is no `integration` build tag: a plain `go test ./...` runs every test.

## Ids and the Context

`fw.Ids` generates TSIDs, the same 13-character Crockford ids as the Java framework, with the same
layout (milliseconds in bits 63 to 22) and the same monotonic rule; `Prefixed` adds a typed prefix
(`ord_...`). `fw.Context` is the core contract's context: a client and a correlation id always,
the principal, application, causation and execution ids and permissions when present. It is
passed as a parameter beside `context.Context`, never stored in one. Every id is a small value type
built by `Parse...` (input) or `Must...` (literals); `fwvet` checks the `Must...` literals at lint
time.

## The conformance vectors

The Java tests write them (`./mvnw -q verify -Dconformance.update=true` from the repository root);
the Go tests only compare: `go test ./internal/conformance/` from `go/`. JSON is compared as parsed
values, `sql/` and `lua/` files as exact bytes, as `conformance/README.md` says. A Go mismatch
means Go is wrong, or the vector shows a Java bug, which is fixed in Java first.
