package flowcatalyst

import (
	"errors"
	"strings"
	"testing"
	"time"
)

// The signature rules shared with the Go, TypeScript and Java SDK verifiers, as the Java module's
// DeliverySignatureTest states them. Each rule has a test that fails when the rule is broken.

const (
	sigSecret = "test-signing-secret"
	sigBody   = `{"jobCode":"orders:ops:cleanup:run"}`
)

var sigNow = time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC)

func body(s string) func() ([]byte, error) { return func() ([]byte, error) { return []byte(s), nil } }

func check(b, sig, ts string) failure {
	return checkWith(b, sig, ts, sigSecret, "", "", sigNow)
}

func checkWith(b, sig, ts, secret, bearer, authorization string, now time.Time) failure {
	f, err := checkSignature(body(b), sig, ts, secret, bearer, authorization, now)
	if err != nil {
		panic(err)
	}
	return f
}

func sign(ts, b string) string { return hmacHex(sigSecret, ts, []byte(b)) }

func at(ts string) failure { return check(sigBody, sign(ts, sigBody), ts) }

func TestTheVectorSharedWithTheOtherSDKs(t *testing.T) {
	// Java SDK WebhookSignatureTest.knownVectorMatchesOtherSdks; also computed with openssl
	if got := hmacHex("secret", "2026-08-07T09:32:12.123Z", []byte("hello")); got != "a7d62aade7a79e88792f84e035b72788a3c78a30f46ad265ec8d6fb12a4e9f91" {
		t.Fatalf("got %s", got)
	}
}

func TestTheRouterTestVectorComputedIndependently(t *testing.T) {
	// input of the platform router's TestVerifyMatchesRouterSigner, expected value from
	// printf '%s' "$ts$body" | openssl dgst -sha256 -hmac "$secret"
	got := hmacHex("test-secret-do-not-use-in-prod", "2026-05-24T08:30:00.123Z", []byte(`{"messageId":"msg_TEST123456"}`))
	if got != "0278cfb47ccbf20a2356584cca6e076cbade089ccc961dbe553f0e768cbf9059" {
		t.Fatalf("got %s", got)
	}
}

func TestTheSignedMessageIsTheRawTimestampThenTheRawBody(t *testing.T) {
	ts := "2026-10-09T12:00:00.000Z"
	// neither the body alone, nor the body then the timestamp, nor another spelling of the time
	if check(sigBody, hmacHex(sigSecret, "", []byte(sigBody)), ts) != invalidSignature {
		t.Error("the body alone")
	}
	if check(sigBody, hmacHex(sigSecret, sigBody, []byte(ts)), ts) != invalidSignature {
		t.Error("the body then the timestamp")
	}
	if check(sigBody, sign("1791547200", sigBody), ts) != invalidSignature {
		t.Error("another spelling of the same instant")
	}
	if check(sigBody, sign(ts, sigBody), ts) != "" {
		t.Error("the right message")
	}
}

func TestAValidSignatureTheCaseAndUnixSeconds(t *testing.T) {
	ts := "2026-10-09T12:00:00.000Z"
	if at(ts) != "" {
		t.Error("valid")
	}
	if check(sigBody, strings.ToUpper(sign(ts, sigBody)), ts) != "" {
		t.Error("the signature is compared case-insensitively")
	}
	if at("1791547200") != "" {
		t.Error("unix seconds")
	}
}

func TestTheWindowIsExactly300SecondsPastAnd60SecondsFutureInWholeSeconds(t *testing.T) {
	for ts, want := range map[string]failure{
		"2026-10-09T11:55:00.000Z": "",
		"2026-10-09T11:54:59.999Z": timestampExpired,
		"1791546900":               "", // 300 s old
		"1791546899":               timestampExpired,
		"2026-10-09T12:01:00.999Z": "",
		"1791547260":               "",
		"1791547261":               timestampFuture,
		"2026-10-09T12:01:01.000Z": timestampFuture,
	} {
		if got := at(ts); got != want {
			t.Errorf("%s: %q, want %q", ts, got, want)
		}
	}
	ts := "2026-10-09T12:00:00.000Z"
	if got := checkWith(sigBody, sign(ts, sigBody), ts, sigSecret, "", "", sigNow.Add(time.Hour)); got != timestampExpired {
		t.Errorf("a replay an hour later: %q", got)
	}
}

func TestAnEmptySecretFailsClosedWhateverIsSent(t *testing.T) {
	ts := "2026-10-09T12:00:00.000Z"
	for _, sig := range []string{sign(ts, sigBody), strings.Repeat("0", 64), ""} {
		if got := checkWith(sigBody, sig, ts, "", "", "", sigNow); got != missingSecret {
			t.Errorf("signature %q: %q", sig, got)
		}
	}
}

func TestMissingHeadersAreRejected(t *testing.T) {
	ts := "2026-10-09T12:00:00.000Z"
	sig := sign(ts, sigBody)
	if check(sigBody, "", ts) != missingSignature || check(sigBody, sig, "") != missingTimestamp {
		t.Error("missing headers")
	}
}

func TestUnparseableTimestampsAreRejected(t *testing.T) {
	for _, ts := range []string{"not-a-time", "2026-10-09 12:00:00", "١٧٩١٥٤٧٢٠٠", strings.Repeat("1", 19), "2026-10-09T12:00:00.000Z" + strings.Repeat(" ", 60), "-1791547200", "1791547200 ", "0x6c"} {
		if got := check(sigBody, sign(ts, sigBody), ts); got != invalidTimestamp {
			t.Errorf("%q: %q", ts, got)
		}
	}
}

func TestAnyChangeToTheSignatureIsRejected(t *testing.T) {
	ts := "2026-10-09T12:00:00.000Z"
	sig := sign(ts, sigBody)
	flip := func(c byte) string {
		if c == 'a' {
			return "b"
		}
		return "a"
	}
	for _, bad := range []string{flip(sig[0]) + sig[1:], sig[:63] + flip(sig[63]), sig[1:], sig + "0", strings.Repeat("0", 64)} {
		if got := check(sigBody, bad, ts); got != invalidSignature {
			t.Errorf("%q: %q", bad, got)
		}
	}
}

func TestAnyChangeToTheBodyOrTheSecretIsRejected(t *testing.T) {
	ts := "2026-10-09T12:00:00.000Z"
	sig := sign(ts, sigBody)
	if check(sigBody+"\n", sig, ts) != invalidSignature || check(strings.ReplaceAll(sigBody, ":", " : "), sig, ts) != invalidSignature ||
		checkWith(sigBody, sig, ts, sigSecret+"x", "", "", sigNow) != invalidSignature {
		t.Error("a change was accepted")
	}
}

func TestTheBodyIsNotReadUntilTheHeadersPass(t *testing.T) {
	reads := 0
	read := func() ([]byte, error) { reads++; return []byte(sigBody), nil }
	ts := "2026-10-09T12:00:00.000Z"
	sig := sign(ts, sigBody)
	for _, c := range []struct{ sig, ts, secret string }{
		{"", ts, sigSecret}, {sig, "", sigSecret}, {sig, "2020-01-01T00:00:00.000Z", sigSecret}, {sig, ts, ""}, {"abc", ts, sigSecret},
	} {
		if _, err := checkSignature(read, c.sig, c.ts, c.secret, "", "", sigNow); err != nil {
			t.Fatal(err)
		}
	}
	if reads != 0 {
		t.Fatalf("%d reads before the headers passed", reads)
	}
	if f, _ := checkSignature(read, sig, ts, sigSecret, "", "", sigNow); f != "" || reads != 1 {
		t.Fatalf("%q after %d reads", f, reads)
	}
	failing := func() ([]byte, error) { return nil, errors.New("too large") }
	if _, err := checkSignature(failing, sig, ts, sigSecret, "", "", sigNow); err == nil {
		t.Fatal("a body that cannot be read was not reported")
	}
}

func TestTheBearerGateIsAndedWithTheSignature(t *testing.T) {
	ts := "2026-10-09T12:00:00.000Z"
	sig := sign(ts, sigBody)
	with := func(sig, bearer, authorization string) failure {
		return checkWith(sigBody, sig, ts, sigSecret, bearer, authorization, sigNow)
	}
	for _, c := range []struct {
		sig, auth string
		want      failure
	}{
		{sig, "Bearer tok-123", ""},
		{sig, "", missingBearer},
		{sig, "bearer tok-123", missingBearer},
		{sig, "Bearer tok-124", invalidBearer},
		{sig, "Bearer tok-1234", invalidBearer},
		{sig, "Bearer ", invalidBearer},
		{strings.Repeat("0", 64), "Bearer tok-123", invalidSignature}, // a right bearer never stands in for a wrong signature
	} {
		if got := with(c.sig, "tok-123", c.auth); got != c.want {
			t.Errorf("Authorization %q: %q, want %q", c.auth, got, c.want)
		}
	}
	if checkBearer("tok", "BearerXtok") != missingBearer || checkBearer("tok", "Bearer") != missingBearer {
		t.Error("a scheme that is not exactly 'Bearer ' was accepted")
	}
	if checkBearer("", "") != "" || checkBearer("", "Bearer x") != "" || checkBearer("t", "Bearer t") != "" || checkBearer("t", "Bearer u") != invalidBearer {
		t.Error("the bearer gate alone")
	}
}

func TestCleanCutsAndNeutralisesText(t *testing.T) {
	for _, c := range []struct {
		in   string
		max  int
		want string
	}{
		{"abc", 10, "abc"},
		{"abcdef", 3, "abc"},
		{"a\nb\rc\td\u200be\u2028f\u2029g", 100, "a b c d e f g"},
		{"a😀", 2, "a"},  // the cut would end on a high surrogate
		{"a😀", 3, "a😀"}, // the pair fits
		{"😀😀", 1, ""},   // cut inside the first pair
		{"é", 1, "é"},
		{"", 5, ""},
		{"abc", 0, ""},
		{"\U000E0001tag", 100, "\U000E0001tag"}, // supplementary format characters stay, as Java checks each UTF-16 unit
	} {
		if got := clean(c.in, c.max); got != c.want {
			t.Errorf("clean(%q, %d) = %q, want %q", c.in, c.max, got, c.want)
		}
	}
}
