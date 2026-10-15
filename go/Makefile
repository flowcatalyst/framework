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
#   sql-check        the examples' checked SQL: schema dir, sqlc vet (db-prepare) and diff, jet code
#                    (needs sqlc v1.31.1 on PATH: go install github.com/sqlc-dev/sqlc/cmd/sqlc@v1.31.1)
#   go-sync-shared   copy the shared statements, scripts and migrations from ../conformance and the
#                    Java modules into the packages that embed them
#   tools            build golangci-lint and NilAway (pinned in tools/go.mod) into bin/
#   fc-modfile       write flowcatalyst/go.local.work and go.local.mod (not committed): a replace of the
#                    fcsdk module by the checkout at $(FCSDK) (default: the platform repository
#                    beside this one). The flowcatalyst targets use it when it exists. fcsdk has no
#                    release yet, so go.mod alone cannot resolve it; once it does, FCSDK= (empty)
#                    builds from go.mod as a consumer would.
#   fc-tidy          go mod tidy of the flowcatalyst module through go.local.mod, then go.mod and
#                    go.sum rewritten from it without the fcsdk replace

GO ?= go
TOOL := $(GO) tool -modfile=$(CURDIR)/tools/go.mod
MODULE := github.com/flowcatalyst/framework/go

# The platform repository's fcsdk module, for local development only (never committed as a replace).
FCSDK ?= $(abspath $(CURDIR)/../../flowcatalyst-go/pkg/fcsdk)
# Run a command in the flowcatalyst module, with the fcsdk checkout when there is one.
# (a workspace file, not -modfile: golangci-lint and NilAway fail under GOFLAGS=-modfile, and go tool -modfile
# cannot run in a workspace, so they are built into bin/ first.)
FC = cd flowcatalyst && if [ -f go.local.work ]; then export GOWORK=$(CURDIR)/flowcatalyst/go.local.work; fi;
LINT := $(CURDIR)/bin/golangci-lint
NILAWAY := $(CURDIR)/bin/nilaway

.PHONY: go-check fmt fmt-check lint vet nilaway test sql-check go-sync-shared fc-modfile fc-tidy tools

go-check: fc-modfile fmt-check lint vet nilaway test sql-check

# The pinned tools (tools/go.mod), built by the local Go into bin/.
tools:
	$(GO) build -modfile=$(CURDIR)/tools/go.mod -o bin/golangci-lint github.com/golangci/golangci-lint/v2/cmd/golangci-lint
	$(GO) build -modfile=$(CURDIR)/tools/go.mod -o bin/nilaway go.uber.org/nilaway/cmd/nilaway

fc-modfile:
	@if [ -n "$(FCSDK)" ] && [ -d "$(FCSDK)" ]; then \
	  printf 'go 1.27\n\nuse .\n\nreplace github.com/flowcatalyst/flowcatalyst/pkg/fcsdk => %s\n' "$(FCSDK)" > flowcatalyst/go.local.work; \
	  cp flowcatalyst/go.mod flowcatalyst/go.local.mod; \
	  if [ -f flowcatalyst/go.sum ]; then cp flowcatalyst/go.sum flowcatalyst/go.local.sum; fi; \
	  printf '\nreplace github.com/flowcatalyst/flowcatalyst/pkg/fcsdk => %s\n' "$(FCSDK)" >> flowcatalyst/go.local.mod; \
	else \
	  rm -f flowcatalyst/go.local.mod flowcatalyst/go.local.sum flowcatalyst/go.local.work; \
	  echo "note: no fcsdk checkout at '$(FCSDK)'; the flowcatalyst module resolves fcsdk from go.mod (it needs a released version)"; \
	fi

fc-tidy: fc-modfile
	@test -f flowcatalyst/go.local.mod || { echo "fc-tidy needs the fcsdk checkout (FCSDK=...)"; exit 1; }
	cd flowcatalyst && GOWORK=off GOFLAGS=-modfile=go.local.mod go mod tidy
	grep -v '^replace github.com/flowcatalyst/flowcatalyst/pkg/fcsdk' flowcatalyst/go.local.mod | cat -s > flowcatalyst/go.mod
	cp flowcatalyst/go.local.sum flowcatalyst/go.sum

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

go-sync-shared:
	@sh internal/conformance/sync-shared.sh
