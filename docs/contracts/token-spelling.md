# Contract: the spelling of signed and sealed values

Status: written 2026-11-19, before the code, for every implementation (Java, Go; Rust has no auth
or push code yet and follows this text when it gets some). Owner's ruling (2026-11-19): "I would say
yes to strict decoding in the sdks and the framework. And lenient on 3rd party." The platform made
the same change the same day (`flowcatalyst-go`, `docs/platform-bug-fixes.md`, F53, helper
`internal/platform/auth/compactjws`).

## 1. Why

A base64url string whose length is not a multiple of four ends in a character with spare low bits:
two when the length is 3 mod 4, four when it is 2 mod 4. A lenient decoder ignores them, so several
strings decode to the same bytes, and a token whose signature was re-spelled that way still
verifies. Lenient decoders also take `=` padding (Java's `Base64.getUrlDecoder()`), and Go's
decoder skips CR and LF anywhere in its input even in its strict mode. Nothing is forged: the bytes
are the same. But one value then has many spellings that all verify, and the day something keys a
value by its text (a denylist, a replay cache, a log correlation) that bites. So the framework
verifies a value only in the spelling it was issued in.

A decoder's own strict mode is not enough (Go's `Strict()` still skips CR and LF; Java has none), so
the rule is checked on the text.

## 2. The rule: canonical base64url

A string is **canonical base64url** when all of these hold (RFC 7515 section 2, which is RFC 4648
section 5 without padding, line breaks or other characters, plus RFC 4648 section 3.5 on the spare
bits):

1. every character is one of `A`-`Z`, `a`-`z`, `0`-`9`, `-`, `_`: no `=` padding, no `+` or `/`
   (the standard alphabet), no space, tab, CR, LF or any other character, anywhere;
2. its length is not 1 mod 4;
3. the spare bits of its last character are zero: when the length is 2 mod 4 the last character is
   one of `A`, `Q`, `g`, `w`; when it is 3 mod 4 it is one of `A E I M Q U Y c g k o s w 0 4 8`.

The empty string is canonical (it is zero bytes). Each byte string then has exactly one spelling,
and for every string accepted, encoding the decoded bytes without padding in the URL-safe alphabet
gives the string back.

A **verifier** of a value listed in section 3 decodes each of the value's base64url parts with this
rule and refuses the value when a part breaks it, with the refusal that part's decoding already has
(section 3). It does not trim, unquote or otherwise clean the value first: what it receives is what
it checks (section 4 says what the HTTP layer removes before that).

An **issuer** of such a value writes every base64url part canonically: a standard unpadded
base64url encoder does (Java `Base64.getUrlEncoder().withoutPadding()`, Go
`base64.RawURLEncoding`, Rust `base64` `URL_SAFE_NO_PAD`, PHP `rtrim(strtr(base64_encode(..),
'+/', '-_'), '=')`). Every encoder in the framework is one of those, so every value the framework
issued, before this rule or after it, is canonical and keeps verifying.

## 3. Where it applies

Everything the framework or the platform issues and the framework verifies:

| Value | Contract | Parts checked | Issued by | A non-canonical part gets |
|---|---|---|---|---|
| platform access token (`Bearer`) | sessions 5.6 | header, payload, signature | the platform | header `malformed`; signature `signature` (a re-spelled payload fails the signature, which is over the text) |
| id_token at the callback | sessions 5.5 | header, payload, signature | the platform | as the access token, `INVALID_ID_TOKEN` |
| field access token (`DPoP`) | sessions 15.2 | header, payload, signature | the platform | as the access token |
| DPoP proof | sessions 15.3 | header, payload, signature, and the header's `jwk` `x` and `y` | the field libraries on the device | `invalid_proof` |
| application nonce (in the proof's `nonce`) | sessions 15.4 | the nonce | the application (the framework) | not a valid nonce: `use_dpop_nonce` |
| sealed cookie (`cookie` driver) | sessions 6.5 | the sealed part (`v1.{kid}.{here}`) | the application (the framework) | does not open: no session, no login record |
| push ticket | push 6.4 | header, payload, signature | the application (the framework's `Pushes.ticket` / `push.Ticket`, or a platform SDK's ticket minter) | a malformed ticket: `400 VALIDATION` |
| push stream token | push 6.2 | payload and MAC | the host (the framework) | `401 STREAM_TOKEN_INVALID` |

"Re-spelled" covers every way of writing the same bytes differently: another last character with
the same significant bits, `=` padding (the right amount or any other), the standard alphabet,
whitespace before, after or inside, and a line break (LF, CR, CRLF) anywhere.

## 4. What is not part of the value

The HTTP syntax around a value is removed before the value is checked, and is not a spelling of it:
optional whitespace around a header field value (RFC 9110 section 5.5; servers drop it), the spaces
between an authorization scheme (`Bearer`, `DPoP`) and its credentials, the double quotes a cookie
value may carry (sessions 5.3), and the percent-decoding of the `st` query parameter. What remains is
checked as it is: no implementation trims the value itself.

## 5. Not covered

- **A third party's value is exempt.** A value issued outside the framework and the platform (an
  external identity provider's ID token, say) is decoded as RFC 7515 allows, leniently about the
  spare bits, because a provider whose encoder left them set would otherwise fail every sign-in and
  nothing is gained by refusing. **The framework verifies no such value today**: the id_token it
  verifies is the platform's own (the platform federates the external providers and issues its own
  id_token; sessions section 1), and so it is strict. An implementation that later verifies an
  external provider's token directly keeps that token lenient and says so where it does.
- **Key material** read from a key set the framework trusts: the platform's JWKS (`n`, `e`; sessions
  5.2). It is fetched by the server, never compared as text, and the `kid` is its identity. It is
  read as before.
- **Configuration**: `auth.cookie-keys` and `push.ticket.key` are written by an operator and parsed
  at start-up as before (surrounding spaces and padding tolerated where they were).
- **Values read without being verified**: the claims of the application's own access token, which
  the FlowCatalyst module reads for its service principal without checking the signature
  (flowcatalyst contract, section 4b).
- **Values compared as text**, which have one spelling by construction: the DPoP `ath`, a ticket's
  `cnf.jkt` against the proof key's thumbprint, PKCE challenges, session handles (stored by a hash of
  the exact text). The delivery signature of the FlowCatalyst receivers is hex (flowcatalyst contract, section
  4), which both implementations lowercase before comparing, so upper-case hex is a second spelling
  that verifies; it is not base64, and this contract does not change it (the ruling of 2026-11-19
  is about base64 spellings).

## 6. Conformance

- `conformance/auth/spelling.json` (written by Java, `framework-auth` `SpellingConformanceTest`):
  the rule on bare strings (each canonical or not, with its bytes), and per value of the auth module
  (access token, field token, DPoP proof, application nonce, sealed cookie) its setup, the value as
  issued (which must verify) and every re-spelling with the answer it must get.
- `conformance/push/spelling.json` (written by Java, `framework-push` `SpellingConformanceTest`):
  the same for push tickets and stream tokens.
- `conformance/auth/issued-before.json` and `conformance/push/issued-before.json` (fixtures, written
  once and never regenerated): values the Go and the Java code issued at framework commit `2360d29`,
  before this contract. Every implementation must still accept each of them.

Go reads them in `go/auth` and `go/push`. Rust has no reader (no auth or push code).

## 7. Compatibility

Nothing the framework or the platform issued stops working: their encoders are canonical (section 2)
and the fixtures of section 6 prove it for the values the framework issues. What stops working is a
value someone re-spelled on the way (a proxy that re-wraps a header, a client that pads, a test that
builds a token by hand with the standard alphabet), or a signer of one's own (a DPoP proof, a push
ticket) built on an encoder that pads or leaves spare bits set.
