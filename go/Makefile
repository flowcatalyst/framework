# The Go framework's checks. Run from the repository root as `make -C go go-check` (what CI runs and
# what a commit needs), or from go/ as `make go-check`. Tools are pinned in tools/go.mod and built by
# the local Go toolchain (golangci-lint v2.14.0, NilAway, gofumpt): gofumpt through
# `go tool -modfile=tools/go.mod`, the other two built into bin/ (target tools).
#
#   go-check         everything below, in order
#   fmt              rewrite the sources with gofumpt
#   fmt-check        fail when a file is not gofumpt-formatted
#   lint             golangci-lint (.golangci.yml) on both modules
#   vet              go vet with the framework's analyzers (cmd/fwvet)
#   nilaway          NilAway over the framework's packages
#   test             go test -race on both modules (PostgreSQL and Redis come from fwtest)
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
LINT := $(CURDIR)/bin/golangci-lint
NILAWAY := $(CURDIR)/bin/nilaway

.PHONY: e2e-orders e2e-tickets go-check fmt fmt-check lint vet nilaway test sql-check go-sync-shared fc-modfile fc-tidy tools

go-check: fc-modfile fmt-check lint vet nilaway test sql-check

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
	$(TOOL) gofumpt -w $$(find . -name '*.go' -not -path './tools/*' -not -path '*/testdata/*' -not -path '*/internal/jet/*')

fmt-check:
	@out=$$($(TOOL) gofumpt -l $$(find . -name '*.go' -not -path './tools/*' -not -path '*/testdata/*' -not -path '*/internal/jet/*')); \
	if [ -n "$$out" ]; then echo "not gofumpt-formatted (run make -C go fmt):"; echo "$$out"; exit 1; fi

lint: fc-modfile tools
	$(LINT) run ./...
	$(FC) $(LINT) run --config ../.golangci.yml ./...

vet: fc-modfile
	$(GO) build -o bin/fwvet ./cmd/fwvet
	$(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...
	$(FC) $(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...

nilaway: fc-modfile tools
	$(NILAWAY) -include-pkgs=$(MODULE) ./...
	$(FC) $(NILAWAY) -include-pkgs=$(MODULE)/flowcatalyst ./...

test: fc-modfile
	$(GO) test -race -count=1 ./...
	$(FC) $(GO) test -race -count=1 ./...

sql-check:
	cd examples/orders-demo && $(GO) run ./tools/fwsql check
	$(FC) cd examples/tickets-trial && $(GO) run ./tools/fwsql check

go-sync-shared:
	@sh internal/conformance/sync-shared.sh

e2e-orders:
	cd ../ui/apps/orders && npx --no-install vite build
	$(FC) $(GO) build -o $(CURDIR)/bin/orders-demo ./examples/orders-demo
	cd ../ui/apps/orders && ORDERS_E2E_BINARY=$(CURDIR)/bin/orders-demo node node_modules/@flowcatalyst/ui/src/e2e/cli.ts playwright test --config ../../../go/examples/orders-demo/e2e/playwright.config.ts

e2e-tickets:
	cd ../ui/apps/tickets && npx --no-install vite build
	$(FC) $(GO) build -o $(CURDIR)/bin/tickets-trial ./examples/tickets-trial
	cd ../ui/apps/tickets && TICKETS_E2E_BINARY=$(CURDIR)/bin/tickets-trial node node_modules/@flowcatalyst/ui/src/e2e/cli.ts playwright test --config ../../../go/flowcatalyst/examples/tickets-trial/e2e/playwright.config.ts
