// Package clientsettings is per-client settings (client-settings contract): a value that differs from
// one client to the next, changes while the application runs, and lives in the database. The
// definitions are code ([Setting], with a typed default and an optional rule); the values are one row
// per client in fw_client_settings, one aggregate ([Settings]) whose version covers all of the
// client's settings. The table and the JSON are the Java framework's, so a Go service can take over a
// Java database.
//
// The framework supplies no use case, endpoint or page for writing: the write is an ordinary use case
// whose plan holds [Repository.Step]. Reading is [Get] on a [Store], straight from the database
// (no cache).
package clientsettings
