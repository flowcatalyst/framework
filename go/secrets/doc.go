// Package secrets resolves per-client secrets (secrets contract): an application keeps, per
// client, a reference to a credential it needs to act for that client (an SFTP password, an API
// key of the client's own system), never the credential itself, and asks the framework to resolve
// the reference for the client it is acting for.
//
//	password, err := resolver.Require(ctx, cx.Client(), secrets.MustParseRef("files:sftp-password"))
//
// The client comes from the caller, never from the reference, and every store keys its lookup by
// (client, name), so a reference cannot name another client's secret or a framework secret. Stores
// are code, not configuration: they are named where the resolver is built, with the driver
// (Env, File), its parameters and a cache policy. Application-wide secrets (a database password,
// the platform client secret) are not this: they are config.Secret settings.
//
// A value lives only in a config.Secret, whose every formatting path is redacted; no error, result
// or log line contains a value or a file's content. Out of scope, as for Java: a compromised host
// or process, the store's own access control, and wiping values from memory (Go strings are
// immutable and the collector moves memory).
package secrets
