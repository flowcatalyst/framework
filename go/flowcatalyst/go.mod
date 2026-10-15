module github.com/flowcatalyst/framework/go/flowcatalyst

go 1.27

require (
	github.com/flowcatalyst/flowcatalyst/pkg/fcsdk v0.0.2
	github.com/flowcatalyst/framework/go v0.0.0
	github.com/jackc/pgx/v5 v5.9.2
)

require (
	github.com/decred/dcrd/dcrec/secp256k1/v4 v4.4.0 // indirect
	github.com/fergusstrange/embedded-postgres v1.34.0 // indirect
	github.com/go-jet/jet/v2 v2.16.1 // indirect
	github.com/goccy/go-json v0.10.6 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.3 // indirect
	github.com/lestrrat-go/blackmagic v1.0.3 // indirect
	github.com/lestrrat-go/httpcc v1.0.1 // indirect
	github.com/lestrrat-go/httprc v1.0.6 // indirect
	github.com/lestrrat-go/iter v1.0.2 // indirect
	github.com/lestrrat-go/jwx/v2 v2.1.6 // indirect
	github.com/lestrrat-go/option v1.0.1 // indirect
	github.com/lib/pq v1.12.3 // indirect
	github.com/segmentio/asm v1.2.1 // indirect
	github.com/xi2/xz v0.0.0-20171230120015-48954b6210f8 // indirect
	go.yaml.in/yaml/v3 v3.0.5 // indirect
	golang.org/x/crypto v0.52.0 // indirect
	golang.org/x/sync v0.23.0 // indirect
	golang.org/x/sys v0.45.0 // indirect
	golang.org/x/text v0.37.0 // indirect
)

// The framework's core module is this repository's go/ directory. A consumer ignores this line (a
// replace in a dependency is not applied) and resolves the module by version.
replace github.com/flowcatalyst/framework/go => ../
