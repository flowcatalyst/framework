# Contract: the Rust build

Status: R0 (2026-10-12). The Rust counterpart of [`build.md`](build.md). Design:
[`../rust/design.md`](../rust/design.md) section 2.

## 1. Where it lives

The `rust/` directory of this repository is one Cargo workspace. It is not a Maven module; the Java
build ignores it. It shares with the Java build the contracts, `ui-kit/` and the conformance
vectors in `conformance/` at the repository root.

## 2. Toolchain and editions

- Edition 2024 for every crate.
- `rust-toolchain.toml` pins `1.98.1` (the FlowCatalyst platform's pin) with `clippy` and
  `rustfmt`; `rust-version = "1.98"` in `[workspace.package]`. Both change together, in one
  commit, deliberately.
- Once published, the MSRV is the pinned toolchain at the time of a minor release and is raised only
  in a minor release.

## 3. Crates and features

- Crate names are working names (`fw-core`, `fw-db`, ...) until the owner settles the name (design
  decision 19). `publish = false` everywhere until then; nothing is reserved on crates.io.
- Drivers are **Cargo features** of their capability crate (`postgres`, `redis`), with the same
  names in every crate. Default features are the minimum that works with no infrastructure (memory
  drivers). Features add drivers and integrations; they never change behaviour, so feature
  unification is always safe.
- Feature `testing` on a capability crate exposes its fakes and its contract test suite (the Java
  test-jars).
- A driver named in configuration whose feature is not compiled in fails start-up with a problem
  that names the feature to enable.
- Files a crate embeds (migrations, SQL statements, Lua scripts) are copies **inside the crate**,
  so the crate can be packaged; a conformance test compares each copy with the shared file or the
  Java resource byte for byte.

## 4. Lints and formatting

- `[workspace.lints]`: the platform's table (`unsafe_code` denied; `absolute_paths` with two
  segments, `wildcard_enum_match_arm`, `unwrap_used`, `expect_used`, `panic`,
  `let_underscore_must_use`, `await_holding_lock`, `await_holding_refcell_ref`, `dbg_macro`, `todo`,
  `unimplemented`), plus `missing_docs`, `unreachable_pub` and `rust_2018_idioms`. Every crate has
  `[lints] workspace = true`.
- `clippy.toml`: tests may unwrap, expect and panic.
- CI runs `cargo fmt --all --check` and `cargo clippy --workspace --all-targets --all-features --
  -D warnings`.

## 5. Supply chain

`cargo deny check` (`rust/deny.toml`): crates.io only; permissive licences only (Apache-2.0, MIT,
BSD, ISC, Zlib, Unicode and similar), no copyleft (no AGPL, GPL, LGPL or MPL); RustSec advisories,
unmaintained and unsound crates refused. Dependencies are declared once in
`[workspace.dependencies]`. `cargo vet` is not required (decision 3).

## 6. CI

`.github/workflows/rust.yml`, run when `rust/`, `conformance/` or the workflow change: format,
clippy, tests (PostgreSQL 18 and Redis as service containers, passed to `fw-test` through
`FW_TEST_DATABASE_URL` and `FW_TEST_REDIS_URL`), and `cargo deny`.

## 7. Release

Not yet: crates.io (Cargo cannot publish to GitHub Packages), tags `rust-v*`, `cargo semver-checks`
in the release workflow, once the owner settles the name.
