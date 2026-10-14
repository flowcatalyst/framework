package fw_test

import (
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func decode(t *testing.T, id string) uint64 {
	t.Helper()
	v, ok := fw.DecodeTSID(id)
	if !ok {
		t.Fatalf("%q does not decode", id)
	}
	return v
}

func TestIdsAreMonotonicWhenTheClockStandsStillOrGoesBack(t *testing.T) {
	t.Parallel()
	clock := fwtest.NewClock(time.UnixMilli(1_760_000_000_000))
	ids := fw.NewIds(clock)
	prev := decode(t, ids.Generate())
	for i := range 10_000 { // more than 4096 in one millisecond: borrows the next ones
		if i == 5000 {
			clock.Set(time.UnixMilli(1_700_000_000_000)) // backwards
		}
		v := decode(t, ids.Generate())
		if v <= prev {
			t.Fatalf("id %d: %d is not above %d", i, v, prev)
		}
		prev = v
	}
	if ms := prev >> 22; ms <= 1_760_000_000_000 {
		t.Errorf("10 000 ids in one millisecond must borrow later milliseconds, ended at %d", ms)
	}
}

func TestIdsAreUniqueUnderConcurrency(t *testing.T) {
	t.Parallel()
	ids := fw.NewIds(fw.SystemClock())
	var mu sync.Mutex
	seen := make(map[string]bool)
	var wg sync.WaitGroup
	for range 8 {
		wg.Go(func() {
			local := make([]string, 0, 2000)
			for range 2000 {
				local = append(local, ids.Generate())
			}
			mu.Lock()
			defer mu.Unlock()
			for _, id := range local {
				if seen[id] {
					t.Errorf("duplicate id %s", id)
				}
				seen[id] = true
			}
		})
	}
	wg.Wait()
}

func TestPrefixedIDs(t *testing.T) {
	t.Parallel()
	ids := fw.NewIds(fwtest.NewClock(time.UnixMilli(1_760_000_000_000)))
	id := ids.Prefixed(fw.MustIDPrefix("ord"))
	head, tail, ok := strings.Cut(id, "_")
	if !ok || head != "ord" || !fw.CanonicalTSID(tail) {
		t.Errorf("Prefixed: got %q", id)
	}
	for _, bad := range []string{"", "Ord", "1a", "or_d", "or-d"} {
		if _, err := fw.ParseIDPrefix(bad); err == nil {
			t.Errorf("prefix %q accepted", bad)
		}
	}
}

func TestEncodeDecodeRoundTrip(t *testing.T) {
	t.Parallel()
	for _, v := range []uint64{0, 1, 31, 32, 1 << 62, 1<<63 - 1} {
		s := fw.EncodeTSID(v)
		if got := decode(t, s); got != v {
			t.Errorf("%d -> %s -> %d", v, s, got)
		}
		if !fw.CanonicalTSID(s) {
			t.Errorf("%s is not canonical", s)
		}
	}
	if fw.CanonicalTSID(strings.ToLower("0ABCDEFGHJKMN")) {
		t.Error("lower case text is not canonical")
	}
}

func FuzzDecodeTSID(f *testing.F) {
	for _, s := range []string{"0000000000000", "7ZZZZZZZZZZZZ", "0abcdefghjkmn", "ZZZZZZZZZZZZZ", "000000000000I", ""} {
		f.Add(s)
	}
	f.Fuzz(func(t *testing.T, s string) {
		v, ok := fw.DecodeTSID(s)
		if !ok {
			return
		}
		if len(s) != 13 {
			t.Fatalf("%q decoded with length %d", s, len(s))
		}
		if fw.CanonicalTSID(s) && fw.EncodeTSID(v) != s {
			t.Fatalf("canonical %q encodes back as %q", s, fw.EncodeTSID(v))
		}
		if s[0] <= '7' && !strings.EqualFold(fw.EncodeTSID(v), s) {
			t.Fatalf("%q (first character at most 7) does not round-trip: %q", s, fw.EncodeTSID(v))
		}
	})
}
