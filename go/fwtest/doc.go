// Package fwtest gives tests real infrastructure without sharing it with anything else:
//
//   - PostgreSQL: one embedded PostgreSQL 18 per test binary on a free port, in a temporary
//     directory removed at the end, with a template database prepared once ([Template]) and a
//     fresh database cloned from it for each test ([NewDatabase]), so tests may run in parallel.
//     FW_TEST_DATABASE_URL points the tests at another server (CI's service container) instead.
//   - Redis: a redis-server (or valkey-server) from PATH, started once per test binary on a free
//     port, or the server at FW_TEST_REDIS_URL. Without either, a test that asks for Redis fails
//     with the instruction to install one: Redis tests are never skipped.
//   - Time: a fake [Clock] that tests set and move.
//
// A test binary that uses PostgreSQL or Redis has a TestMain:
//
//	func TestMain(m *testing.M) { fwtest.Main(m) }
//
// Nothing here ever connects to port 15432 (the platform's development database) or binds
// port 8080: free ports are taken from 127.0.0.1:0 and those two are skipped, and a
// FW_TEST_DATABASE_URL on port 15432 is refused.
package fwtest
