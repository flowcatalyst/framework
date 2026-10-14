package fw

import (
	"errors"
	"math/rand/v2" // TSID sequence and random bits need no cryptographic randomness (as in Java)
	"sync/atomic"
)

// TSIDs (write-path contract section 8): 13 Crockford base-32 characters encoding a 64-bit value
// whose top 42 bits are the milliseconds since the Unix epoch, then a 12-bit sequence, then 10
// random bits. The layout, the alphabet and the decoding rules are shared with the Java framework
// and checked by conformance/tsid/tsid.json.

const (
	tsidAlphabet   = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	tsidLength     = 13
	tsidMillisMask = 0x3FF_FFFF_FFFF
	tsidSeqBits    = 12
	tsidSeqMask    = 0xFFF
	tsidRandomBits = 10
)

var tsidDecode = func() [128]int8 {
	var t [128]int8
	for i := range t {
		t[i] = -1
	}
	for i := range len(tsidAlphabet) {
		t[tsidAlphabet[i]] = int8(i)
	}
	return t
}()

// Ids generates TSIDs. Monotonic within one Ids: when the clock does not advance or goes
// backwards, the generator continues from the last value instead of repeating or going back, and
// more than 4096 ids in one millisecond borrow the next one. Safe for concurrent use. An
// application has one, from [Boot.Ids].
type Ids struct {
	clock Clock
	state atomic.Uint64 // last milliseconds << 12 | last sequence
}

// NewIds returns a generator reading the given clock.
func NewIds(clock Clock) *Ids {
	return &Ids{clock: clock}
}

// Generate returns a new 13-character TSID.
func (g *Ids) Generate() string {
	now := uint64(g.clock.Now().UnixMilli()) //nolint:gosec // milliseconds since 1970 are positive
	for {
		old := g.state.Load()
		lastMs := old >> tsidSeqBits
		lastSeq := old & tsidSeqMask
		var ms, seq uint64
		switch {
		case now > lastMs:
			ms, seq = now, rand.Uint64N(tsidSeqMask+1) //nolint:gosec // not a secret
		case lastSeq < tsidSeqMask:
			ms, seq = lastMs, lastSeq+1
		default:
			ms, seq = lastMs+1, rand.Uint64N(tsidSeqMask+1) //nolint:gosec // not a secret
		}
		if g.state.CompareAndSwap(old, ms<<tsidSeqBits|seq) {
			random := rand.Uint64N(1 << tsidRandomBits) //nolint:gosec // not a secret
			return EncodeTSID((ms&tsidMillisMask)<<(tsidSeqBits+tsidRandomBits) | seq<<tsidRandomBits | random)
		}
	}
}

// Prefixed returns prefix + "_" + a new TSID, for example "ord_0ABCDEFGHJKMN".
func (g *Ids) Prefixed(prefix IDPrefix) string {
	if prefix.IsZero() {
		panic("fw: Ids.Prefixed with the zero IDPrefix")
	}
	return prefix.v + "_" + g.Generate()
}

// IDPrefix is the prefix of a typed id, matching [a-z][a-z0-9]*.
type IDPrefix struct{ v string }

// ErrInvalidPrefix is returned by [ParseIDPrefix] for text that is not a valid prefix.
var ErrInvalidPrefix = errors.New("TSID prefix must match [a-z][a-z0-9]*")

// ParseIDPrefix checks a prefix.
func ParseIDPrefix(s string) (IDPrefix, error) {
	if !ValidIDPrefix(s) {
		return IDPrefix{}, errors.Join(ErrInvalidPrefix, errors.New("got "+quote(s)))
	}
	return IDPrefix{v: s}, nil
}

// MustIDPrefix is [ParseIDPrefix] for literals in source; it panics on a bad prefix.
func MustIDPrefix(s string) IDPrefix {
	p, err := ParseIDPrefix(s)
	if err != nil {
		panic(err)
	}
	return p
}

// String returns the prefix.
func (p IDPrefix) String() string { return p.v }

// IsZero reports whether p is the zero value, which no framework API accepts.
func (p IDPrefix) IsZero() bool { return p.v == "" }

// ValidIDPrefix reports whether s matches [a-z][a-z0-9]*.
func ValidIDPrefix(s string) bool {
	if s == "" || s[0] < 'a' || s[0] > 'z' {
		return false
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

// EncodeTSID returns the canonical 13-character text of a value.
func EncodeTSID(v uint64) string {
	var out [tsidLength]byte
	for i := tsidLength - 1; i >= 0; i-- {
		out[i] = tsidAlphabet[v&0x1F]
		v >>= 5
	}
	return string(out[:])
}

// DecodeTSID returns the value of a 13-character TSID text. Decoding is case-insensitive and
// accepts only the Crockford alphabet without I, L, O and U. A first character above 7 is not
// refused: the value wraps, as in the Java code, so such a text is valid but not canonical.
func DecodeTSID(s string) (uint64, bool) {
	if len(s) != tsidLength {
		return 0, false
	}
	var v uint64
	for i := range tsidLength {
		c := s[i]
		if c >= 'a' && c <= 'z' {
			c -= 'a' - 'A'
		}
		if c >= 128 || tsidDecode[c] < 0 {
			return 0, false
		}
		v = v<<5 | uint64(tsidDecode[c]) //nolint:gosec // checked non-negative above
	}
	return v, true
}

// CanonicalTSID reports whether s is a TSID in its canonical form: it decodes, it is what
// [EncodeTSID] gives back for its value, and its first character is at most '7', so its value is
// a non-negative 64-bit signed integer as every generated TSID is (the Java framework's rule; the
// text "8000000000000" round-trips but is not canonical).
func CanonicalTSID(s string) bool {
	v, ok := DecodeTSID(s)
	return ok && s[0] <= '7' && EncodeTSID(v) == s
}

// ValidTSID reports whether s decodes as a TSID.
func ValidTSID(s string) bool {
	_, ok := DecodeTSID(s)
	return ok
}
