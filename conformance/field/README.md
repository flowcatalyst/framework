# Field-worker vectors

`jwks.json` and `cases.json` are the platform's generated field-token vectors, copied unchanged from
`flowcatalyst-go/api/field-fixtures/` at platform commit `0f3f11ab` (branch `feat/field-sign-in-options`) (field fixtures:
access tokens RS256, proofs ES256; 21 cases: `missing_exp` added after `b8c04573`; `valid_unenrolled`,
`valid_impersonated_supervisor`, `valid_impersonated_principal` and `valid_test` added 2026-11-06, each with a
`principal` object the tests assert). Do not edit them here: regenerate them in the platform (`tools/field-fixtures`)
and copy again, noting the new commit in this file.

Contract: [sessions-and-login section 15](../../docs/contracts/sessions-and-login.md) (15.9).

Each case gives `accessToken`, `dpopProof`, `method`, `url`, `now` and `expect` (`valid`, or the
refusal reason). The verifier is configured with the file's `issuer` and `appCode` and the keys in
`jwks.json`, and each case is checked at its own `now`.

Two things differ from a running application, both by design:

- The vectors carry the **platform's** nonces, which an application cannot check (15.4), so the run
  switches the application-nonce check off. Nonces have their own tests.
- The `stale_proof` case has an `iat` one hour before `now`. The platform's SDK verifier accepts five
  minutes; an application accepts a proof within 24 hours (15.3), because field devices keep bad
  time. The test-only run therefore also narrows the proof window to five minutes (15.9).

The `invalid_token` expectation covers the signature-level refusals of 5.5 (algorithm, key,
signature); a token without `exp` (`missing_exp`) is `expired` by 5.5 and counts as a match. The Java reasons `alg`, `signature`, `kid`, `unknown_kid`, `crit` and
`malformed` all map to it.

Run by `FieldConformanceTest` in `framework-auth` (Java) and `TestFieldConformance` in `go/auth` (Go).
