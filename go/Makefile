# The Go framework's checks. Run from the repository root as `make -C go go-check` (what CI runs and
# what a commit needs), or from go/ as `make go-check`. Tools are pinned in tools/go.mod and built by
# the local Go toolchain (golangci-lint v2.14.0, NilAway, gofumpt): `go tool -modfile=tools/go.mod`.
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

GO ?= go
TOOL := $(GO) tool -modfile=$(CURDIR)/tools/go.mod
MODULE := github.com/flowcatalyst/framework/go

.PHONY: go-check fmt fmt-check lint vet nilaway test sql-check go-sync-shared

go-check: fmt-check lint vet nilaway test sql-check

fmt:
	$(TOOL) gofumpt -w $$(find . -name '*.go' -not -path './tools/*' -not -path '*/testdata/*' -not -path '*/internal/jet/*')

fmt-check:
	@out=$$($(TOOL) gofumpt -l $$(find . -name '*.go' -not -path './tools/*' -not -path '*/testdata/*' -not -path '*/internal/jet/*')); \
	if [ -n "$$out" ]; then echo "not gofumpt-formatted (run make -C go fmt):"; echo "$$out"; exit 1; fi

lint:
	$(TOOL) golangci-lint run ./...
	cd flowcatalyst && $(TOOL) golangci-lint run --config ../.golangci.yml ./...

vet:
	$(GO) build -o bin/fwvet ./cmd/fwvet
	$(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...
	cd flowcatalyst && $(GO) vet -vettool=$(CURDIR)/bin/fwvet ./...

nilaway:
	$(TOOL) nilaway -include-pkgs=$(MODULE) ./...

test:
	$(GO) test -race -count=1 ./...
	cd flowcatalyst && $(GO) test -race -count=1 ./...

sql-check:
	cd examples/orders-demo && $(GO) run ./tools/fwsql check

go-sync-shared:
	@sh internal/conformance/sync-shared.sh
