module github.com/flowcatalyst/framework/go/ui/fwui

go 1.27

require (
	github.com/a-h/templ v0.3.1020
	github.com/andybalholm/brotli v1.2.1
	github.com/flowcatalyst/framework/go v0.0.0
	github.com/flowcatalyst/framework/go/ui v0.0.0
	golang.org/x/net v0.62.0
)

require (
	github.com/a-h/parse v0.0.0-20250122154542-74294addb73e // indirect
	github.com/aymerick/douceur v0.2.0 // indirect
	github.com/cenkalti/backoff/v4 v4.3.0 // indirect
	github.com/cli/browser v1.3.0 // indirect
	github.com/fatih/color v1.16.0 // indirect
	github.com/fergusstrange/embedded-postgres v1.34.0 // indirect
	github.com/fsnotify/fsnotify v1.7.0 // indirect
	github.com/gorilla/css v1.0.1 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/pgx/v5 v5.9.2 // indirect
	github.com/jackc/puddle/v2 v2.2.3 // indirect
	github.com/lib/pq v1.12.3 // indirect
	github.com/mattn/go-colorable v0.1.13 // indirect
	github.com/mattn/go-isatty v0.0.20 // indirect
	github.com/microcosm-cc/bluemonday v1.0.27 // indirect
	github.com/natefinch/atomic v1.0.1 // indirect
	github.com/xi2/xz v0.0.0-20171230120015-48954b6210f8 // indirect
	github.com/yuin/goldmark v1.7.17 // indirect
	go.yaml.in/yaml/v3 v3.0.5 // indirect
	golang.org/x/mod v0.41.0 // indirect
	golang.org/x/sync v0.24.0 // indirect
	golang.org/x/sys v0.49.0 // indirect
	golang.org/x/text v0.43.0 // indirect
	golang.org/x/tools v0.51.0 // indirect
)

// The framework's core module is this repository's go/ directory and the ui module is go/ui. A consumer
// ignores these lines (a replace in a dependency is not applied) and resolves both by version.
replace (
	github.com/flowcatalyst/framework/go => ../..
	github.com/flowcatalyst/framework/go/ui => ..
)

tool github.com/a-h/templ/cmd/templ
