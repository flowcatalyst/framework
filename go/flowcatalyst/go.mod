module github.com/flowcatalyst/framework/go/flowcatalyst

go 1.27

require (
	github.com/flowcatalyst/framework/go v0.3.1
	github.com/flowcatalyst/go-sdk v0.0.19
	github.com/jackc/pgx/v5 v5.13.0
)

require (
	github.com/cespare/xxhash/v2 v2.3.0 // indirect
	github.com/decred/dcrd/dcrec/secp256k1/v4 v4.4.1 // indirect
	github.com/fergusstrange/embedded-postgres v1.34.0 // indirect
	github.com/go-jet/jet/v2 v2.16.1 // indirect
	github.com/goccy/go-json v0.12.0 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/jackc/pgpassfile v1.0.0 // indirect
	github.com/jackc/pgservicefile v0.0.0-20240606120523-5a60cdf6a761 // indirect
	github.com/jackc/puddle/v2 v2.2.3 // indirect
	github.com/lestrrat-go/blackmagic v1.0.4 // indirect
	github.com/lestrrat-go/httpcc v1.0.1 // indirect
	github.com/lestrrat-go/httprc v1.0.6 // indirect
	github.com/lestrrat-go/iter v1.0.2 // indirect
	github.com/lestrrat-go/jwx/v2 v2.1.7 // indirect
	github.com/lestrrat-go/option v1.0.1 // indirect
	github.com/lib/pq v1.12.3 // indirect
	github.com/redis/go-redis/v9 v9.23.0 // indirect
	github.com/segmentio/asm v1.2.1 // indirect
	github.com/xi2/xz v0.0.0-20171230120015-48954b6210f8 // indirect
	go.uber.org/atomic v1.12.0 // indirect
	go.yaml.in/yaml/v3 v3.0.6 // indirect
	golang.org/x/crypto v0.59.0 // indirect
	golang.org/x/sync v0.25.0 // indirect
	golang.org/x/sys v0.50.0 // indirect
	golang.org/x/text v0.44.0 // indirect
)

// The framework's core module is this repository's go/ directory. A consumer ignores this line (a
// replace in a dependency is not applied) and resolves the module by version.
replace github.com/flowcatalyst/framework/go => ../
