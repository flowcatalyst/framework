package b64url_test

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"math/rand/v2"
	"testing"

	"github.com/flowcatalyst/framework/go/internal/b64url"
	"github.com/flowcatalyst/framework/go/internal/b64url/b64urltest"
)

// reencodes is what the rule means, written without it: the standard encoder gives the text back.
func reencodes(text string) bool {
	b, err := base64.RawURLEncoding.DecodeString(text)
	return err == nil && base64.RawURLEncoding.EncodeToString(b) == text
}

func TestEveryShortStringIsCanonicalExactlyWhenItReencodesToItself(t *testing.T) {
	t.Parallel()
	chars := b64urltest.Alphabet + "=+/ \t\r\n."
	k := len(chars)
	checked := 0
	var walk func(prefix string, left int)
	walk = func(prefix string, left int) {
		_, err := b64url.Decode(prefix)
		if (err == nil) != reencodes(prefix) {
			t.Errorf("%q: Decode error %v, reencodes %v", prefix, err, reencodes(prefix))
		}
		checked++
		if left == 0 {
			return
		}
		for i := range k {
			walk(prefix+chars[i:i+1], left-1)
		}
	}
	walk("", 3)
	if want := 1 + k + k*k + k*k*k; checked != want {
		t.Fatalf("checked %d strings, want %d", checked, want)
	}
}

func TestEncodedBytesAreCanonical(t *testing.T) {
	t.Parallel()
	r := rand.New(rand.NewPCG(2026, 1119))
	for n := range 601 {
		b := make([]byte, n)
		for i := range b {
			b[i] = byte(r.Uint32())
		}
		got, err := b64url.Decode(b64url.Encode(b))
		if err != nil || !bytes.Equal(got, b) {
			t.Fatalf("%d bytes: %v", n, err)
		}
	}
}

// TestGoStrictModeIsNotEnough records why the package checks the text: Go's own strict decoder skips
// line breaks, and its lenient one ignores the spare bits.
func TestGoStrictModeIsNotEnough(t *testing.T) {
	t.Parallel()
	mac := bytes.Repeat([]byte{0xA5}, 32)
	canonical := b64url.Encode(mac)
	if _, err := base64.RawURLEncoding.Strict().DecodeString(canonical[:20] + "\r\n" + canonical[20:]); err != nil {
		t.Fatalf("Go's strict decoder now refuses CRLF (%v): the text check may be redundant, not wrong", err)
	}
	if _, err := base64.RawURLEncoding.DecodeString(b64urltest.LastCharacterSpellings(canonical)[0]); err != nil {
		t.Fatal(err)
	}
	for _, s := range b64urltest.Of(t, canonical, 0) {
		if _, err := b64url.Decode(s.Value); err == nil {
			t.Errorf("%s: accepted %q", s.Name, s.Value)
		}
	}
}

func TestTheLastCharacterTable(t *testing.T) {
	t.Parallel()
	var two, three []byte
	for i := range len(b64urltest.Alphabet) {
		c := b64urltest.Alphabet[i : i+1]
		if _, err := b64url.Decode("A" + c); err == nil {
			two = append(two, c[0])
		}
		if _, err := b64url.Decode("AA" + c); err == nil {
			three = append(three, c[0])
		}
	}
	if string(two) != "AQgw" || string(three) != "AEIMQUYcgkosw048" {
		t.Fatalf("2 mod 4: %s, 3 mod 4: %s", two, three)
	}
}

// TestSegmentVectors runs the "segments" of conformance/auth/spelling.json (written by Java).
func TestSegmentVectors(t *testing.T) {
	t.Parallel()
	var v struct {
		Segments []struct {
			Text      string `json:"text"`
			Canonical bool   `json:"canonical"`
			Hex       string `json:"hex"`
		} `json:"segments"`
	}
	b64urltest.Read(t, "auth/spelling.json", &v)
	if len(v.Segments) < 50 {
		t.Fatalf("only %d segments", len(v.Segments))
	}
	for _, s := range v.Segments {
		got, err := b64url.Decode(s.Text)
		if (err == nil) != s.Canonical {
			t.Errorf("%q: canonical %v, Decode error %v", s.Text, s.Canonical, err)
		}
		if s.Canonical && hex.EncodeToString(got) != s.Hex {
			t.Errorf("%q: %x, want %s", s.Text, got, s.Hex)
		}
	}
}

// FuzzDecodeIsOneToOne: an accepted text re-encodes to itself, and every text that re-encodes to
// itself is accepted.
func FuzzDecodeIsOneToOne(f *testing.F) {
	for _, s := range []string{"", "_w", "_x", "__8", "__9", "____", "_w==", "AA\nA", "+w", " AA", "AAAAA"} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, text string) {
		got, err := b64url.Decode(text)
		if err == nil && b64url.Encode(got) != text {
			t.Fatalf("accepted %q, which re-encodes to %q", text, b64url.Encode(got))
		}
		if (err == nil) != reencodes(text) {
			t.Fatalf("%q: Decode error %v, reencodes %v", text, err, reencodes(text))
		}
	})
}
