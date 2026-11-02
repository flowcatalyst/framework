# The Go framework's checks. Run from the repository root as `make -C go go-check` (what CI runs and
# what a commit needs), or from go/ as `make go-check`. Tools are pinned in tools/go.mod and built by
# the local Go toolchain (golangci-lint v2.14.0, NilAway, gofumpt): gofumpt through
# `go tool -modfile=tools/go.mod`, the other two built into bin/ (target tools).
#
#   go-check         everything below, in order
#   fmt              rewrite the sources with gofumpt
#   fmt-check        fail when a file is not gofumpt-formatted
#   lint             golangci-lint (.golangci.yml) on all four modules (core, flowcatalyst, ui, ui/fwui)
#   vet              go vet with the framework's analyzers (cmd/fwvet)
#   nilaway          NilAway over the framework's packages
#   test             go test -race on all four modules (PostgreSQL and Redis come from fwtest)
#   ui-generate      templ generate in the ui and ui/fwui modules (the generated *_templ.go files are committed)
#   ui-verify        ui-generate, then fail when a generated file changed or appeared: the committed
#                    *_templ.go files are the templates' output (templ v0.3.1020, pinned by ui/go.mod and ui/fwui/go.mod)
#   sql-check        the examples' checked SQL (orders-demo, and the tickets-trial of the flowcatalyst module): schema
#                    dir, sqlc vet (db-prepare) and diff, jet code
#                    (needs sqlc v1.31.1 on PATH: go install github.com/sqlc-dev/sqlc/cmd/sqlc@v1.31.1)
#   go-sync-shared   copy the shared statements, scripts and migrations from ../conformance and the
#                    Java modules into the packages that embed them
#   e2e-orders       the orders SPA's browser suite (ui/apps/orders/e2e) against the Go orders-demo with its
#                    whole admin area (the command of the flowcatalyst module, which adds the platform
#                    sections and stands up a fake platform): builds the SPA, the launcher binary into bin/, and
#                    runs Playwright through the kit (Chromium; needs the ui workspace installed). Ports 4392
#                    and 4393, so not while the Java suite runs.
#   e2e-tickets      the tickets SPA's browser suite (ui/apps/tickets/e2e, 24 tests) against the Go tickets-trial
#                    (flowcatalyst/examples/tickets-trial): builds the SPA, the launcher binary into bin/, and runs
#                    Playwright through the kit. Ports 4394 and 4395, so not while the Java tickets suite runs.
#   e2e-orders-templ the Go server-rendered orders demo (ui/fwui/examples/orders-demo-templ) against the Java demo's
#                    browser check (examples/orders-demo-jte/e2e/browser-check.mjs, run unchanged). Builds the
#                    binary into bin/, starts it on a free 127.0.0.1 port (never 8080 or 15432), runs the check
#                    (Playwright and Chromium from the ui workspace) and stops the server by its PID.
#   release-go-ui    tag and push a release of the go/ui module: BUMP=patch|minor|major|X.Y.Z (tag go/ui/vX.Y.Z;
#                    checks a clean main, no replace and no framework require in ui/go.mod, ui-verify and the
#                    module's tests, then asks before tagging and pushing; RELEASE_DRY_RUN=1 checks only)
#   tools            build golangci-lint and NilAway (pinned in tools/go.mod) into bin/
#   fc-modfile       with FCSDK=<path> set, write flowcatalyst/go.local.work and go.local.mod (not committed):
#                    a replace of the fcsdk module by that checkout, to develop against unreleased fcsdk
#                    (FCSDK=$(HOME)/Developer/flowcatalyst-go/pkg/fcsdk). By default FCSDK is empty and the
#                    flowcatalyst module builds from the released fcsdk in go.mod, as a consumer would.
#   fc-tidy          go mod tidy of the flowcatalyst module against the released fcsdk (a local checkout is
#                    ignored; bump the version in go.mod first)

GO ?= go
TOOL := $(GO) tool -modfile=$(CURDIR)/tools/go.mod
MODULE := github.com/flowcatalyst/framework/go

# A checkout of the platform repository's fcsdk module, for developing against unreleased fcsdk only (never
# committed as a replace). Empty by default: go.mod's released version is used.
FCSDK ?=
# Run a command in the flowcatalyst module, with the fcsdk checkout when there is one.
# (a workspace file, not -modfile: golangci-lint and NilAway fail under GOFLAGS=-modfile, and go tool -modfile
# cannot run in a workspace, so they are built into bin/ first.)
FC = cd flowcatalyst && if [ -f go.local.work ]; then export GOWORK=$(CURDIR)/flowcatalyst/go.local.work; fi;
# Run a command in the ui module (go/ui: templ + Datastar server-rendered UI; its own go.mod, so templ,
# goldmark, bluemonday and brotli stay out of the core module; it depends on no framework module).
UI = cd ui &&
# Run a command in the fwui module (go/ui/fwui: the wiring of the ui module into the core module's fw and
# httpfw, and the orders-demo-templ example; its own go.mod, so go/ui stays free of the core module).
FWUI = cd ui/fwui &&
LINT := $(CURDIR)/bin/golangci-lint
NILAWAY := $(CURDIR)/bin/nilaway

.PHONY: release-go-ui e2e-orders e2e-orders-templ e2e-tickets go-check fmt fmt-check lint vet nilaway test sql-check go-sync-shared fc-modfile fc-tidy tools ui-generate ui-verify

go-check: fc-modfile fmt-check lint vet nilaway test sql-check ui-verify

# The pinned tools (tools/go.mod), built by the local Go into bin/.
tools:
	$(GO) build -modfile=$(CURDIR)/tools/go.mod -o bin/golangci-lint github.com/golangci/golangci-lint/v2/cmd/golangci-lint
	$(GO) build -modfile=$(CURDIR)/tools/go.mod -o bin/nilaway go.uber.org/nilaway/cmd/nilaway

fc-modfile:
	@rm -f flowcatalyst/go.local.mod flowcatalyst/go.local.sum flowcatalyst/go.local.work
	@if [ -n "$(FCSDK)" ]; then \
	  test -d "$(FCSDK)" || { echo "FCSDK=$(FCSDK) is not a directory"; exit 1; }; \
	  printf 'go 1.27\n\nuse .\n\nreplace github.com/flowcatalyst/go-sdk => %s\n' "$(abspath $(FCSDK))" > flowcatalyst/go.local.work; \
	  echo "note: flowcatalyst builds against the fcsdk checkout $(abspath $(FCSDK))"; \
	fi

fc-tidy:
	cd flowcatalyst && GOWORK=off go mod tidy

fmt:
	$(TOOL) gofumpt -w $$(find . -name '*.go' -not -name '*_templ.go' -not -path './tools/*' -not -path '*/testdata/*' -not -path '*/internal/jet/*')

fmt-check:
	@out=$$($(TOOL) gofumpt -l $$(find . -name '*.go' -not -name '*_templ.go' -not -path './tools/*' -not -path '*/testdata/*' -not -path '*/internal/jet/*')); \
	if [ -n "$$out" ]; then echo "not gofumpt-formatted (run make -C go fmt):"; echo "$$out"; exit 1; fi

lint: fc-modfile tools
	$(LINT) run ./...
	$(FC) $(LINT) run --config ../.golangci.yml ./...
	$(UI) $(LINT) run --config ../.golangci.yml ./...
	$(FWUI) $(LINT) run --config ../../.golangci.yml ./...

vet: fc-modfile
	$(GO) build -o bin/fwvet ./cmd/fwvet
	$(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...
	$(FC) $(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...
	$(UI) $(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...
	$(FWUI) $(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...

nilaway: fc-modfile tools
	$(NILAWAY) -include-pkgs=$(MODULE) ./...
	$(FC) $(NILAWAY) -include-pkgs=$(MODULE)/flowcatalyst ./...
	$(UI) $(NILAWAY) -include-pkgs=$(MODULE)/ui ./...
	$(FWUI) $(NILAWAY) -include-pkgs=$(MODULE)/ui/fwui ./...

test: fc-modfile
	$(GO) test -race -count=1 ./...
	$(FC) $(GO) test -race -count=1 ./...
	$(UI) $(GO) test -race -count=1 ./...
	$(FWUI) $(GO) test -race -count=1 ./...

sql-check:
	cd examples/orders-demo && $(GO) run ./tools/fwsql check
	$(FC) cd examples/tickets-trial && $(GO) run ./tools/fwsql check

go-sync-shared:
	@sh internal/conformance/sync-shared.sh

ui-generate:
	$(UI) $(GO) tool templ generate
	$(FWUI) $(GO) tool templ generate

# The committed *_templ.go files must be exactly what templ generates from the .templ files.
ui-verify:
	@tmp=$$(mktemp -d); trap 'rm -rf "$$tmp"' EXIT; mkdir "$$tmp/old" "$$tmp/new"; \
	(cd ui && find . -name '*_templ.go' | sort | tar -cf - -T -) | (cd "$$tmp/old" && tar -xf -); \
	($(UI) $(GO) tool templ generate >/dev/null); ($(FWUI) $(GO) tool templ generate >/dev/null); \
	(cd ui && find . -name '*_templ.go' | sort | tar -cf - -T -) | (cd "$$tmp/new" && tar -xf -); \
	if ! diff -rq "$$tmp/old" "$$tmp/new"; then echo "generated templ files are stale (run make -C go ui-generate and commit them)"; exit 1; fi; \
	echo "ui-verify: the generated templ files are current"

e2e-orders:
	cd ../ui/apps/orders && npx --no-install vite build
	$(FC) $(GO) build -o $(CURDIR)/bin/orders-demo ./examples/orders-demo
	cd ../ui/apps/orders && ORDERS_E2E_BINARY=$(CURDIR)/bin/orders-demo node node_modules/@flowcatalyst/ui/src/e2e/cli.ts playwright test --config ../../../go/examples/orders-demo/e2e/playwright.config.ts

e2e-tickets:
	cd ../ui/apps/tickets && npx --no-install vite build
	$(FC) $(GO) build -o $(CURDIR)/bin/tickets-trial ./examples/tickets-trial
	cd ../ui/apps/tickets && TICKETS_E2E_BINARY=$(CURDIR)/bin/tickets-trial node node_modules/@flowcatalyst/ui/src/e2e/cli.ts playwright test --config ../../../go/flowcatalyst/examples/tickets-trial/e2e/playwright.config.ts

e2e-orders-templ:
	$(FWUI) $(GO) build -o $(CURDIR)/bin/orders-demo-templ ./examples/orders-demo-templ
	@port=$$(node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close()})"); \
	case "$$port" in 8080|15432|"") echo "e2e-orders-templ: no usable free port ($$port)"; exit 1;; esac; \
	bin/orders-demo-templ serve --port $$port >bin/orders-demo-templ.log 2>&1 & pid=$$!; \
	i=0; until curl -fs "http://127.0.0.1:$$port/health/live" >/dev/null 2>&1; do \
	  i=$$((i+1)); if [ $$i -gt 100 ] || ! kill -0 $$pid 2>/dev/null; then echo "e2e-orders-templ: the demo did not start (bin/orders-demo-templ.log)"; kill $$pid 2>/dev/null; exit 1; fi; sleep 0.1; \
	done; \
	BASE_URL=http://127.0.0.1:$$port node ../examples/orders-demo-jte/e2e/browser-check.mjs; status=$$?; \
	kill $$pid 2>/dev/null; wait $$pid 2>/dev/null; \
	exit $$status

release-go-ui:
	@[ -n "$(BUMP)" ] || { echo "usage: make -C go release-go-ui BUMP=patch|minor|major|X.Y.Z"; exit 2; }
	@../scripts/release-go-ui.sh "$(BUMP)"
