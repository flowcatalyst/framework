package fw_test

import (
	"context"
	"errors"
	"slices"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
)

func clientIDs(names ...string) []fw.ClientID {
	out := make([]fw.ClientID, len(names))
	for i, n := range names {
		out[i] = fw.MustClientID(n)
	}
	return out
}

func resultClients[R any](rs []fw.ClientResult[R]) []string {
	out := make([]string, len(rs))
	for i, r := range rs {
		out[i] = r.Client.String()
	}
	return out
}

func TestEachClientRunsEachClientOnceInOrderAndOneFailureDoesNotStopTheRest(t *testing.T) {
	t.Parallel()
	base := fw.ContextFor(fw.MustClientID("_platform")).WithPrincipalOfKind(fw.MustPrincipalID("prn_svc"), fw.PrincipalService)
	var seen []string
	boom := errors.New("globex is down")

	res := fw.EachClient(t.Context(), nil, base, clientIDs("acme", "globex", "acme", "initech"),
		func(_ context.Context, cx fw.Context) (string, error) {
			p, _ := cx.Principal()
			seen = append(seen, cx.Client().String()+"/"+p.String())
			if cx.Client().String() == "globex" {
				return "ignored", boom
			}
			return "ok " + cx.Client().String(), nil
		})

	if want := []string{"acme/prn_svc", "globex/prn_svc", "initech/prn_svc"}; !slices.Equal(seen, want) {
		t.Errorf("ran %v, want %v (acme once)", seen, want)
	}
	if got := resultClients(res); !slices.Equal(got, []string{"acme", "globex", "initech"}) {
		t.Fatalf("results %v", got)
	}
	if !res[0].Done() || res[0].Value != "ok acme" {
		t.Errorf("acme: %+v", res[0])
	}
	if !res[1].Failed() || !errors.Is(res[1].Err, boom) || res[1].Value != "" {
		t.Errorf("globex: %+v", res[1])
	}
	if !res[2].Done() || res[2].Value != "ok initech" {
		t.Errorf("initech: %+v", res[2])
	}
	if base.Client().String() != "_platform" {
		t.Error("the base context changed")
	}
}

func TestEachClientRecordsAPanicAsFailedAndGoesOn(t *testing.T) {
	t.Parallel()
	res := fw.EachClient(t.Context(), nil, fw.ContextFor(fw.MustClientID("_platform")), clientIDs("a", "b"),
		func(_ context.Context, cx fw.Context) (int, error) {
			if cx.Client().String() == "a" {
				panic("nil map")
			}
			return 7, nil
		})
	if len(res) != 2 {
		t.Fatalf("results %v", res)
	}
	var pe *fw.PanicError
	if !errors.As(res[0].Err, &pe) || pe.Value != "nil map" {
		t.Errorf("a: %+v", res[0])
	}
	if !res[1].Done() || res[1].Value != 7 {
		t.Errorf("b: %+v", res[1])
	}
}

func TestEachClientStopsStartingClientsOnceTheContextIsDone(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()

	res := fw.EachClient(ctx, nil, fw.ContextFor(fw.MustClientID("_platform")), clientIDs("a", "b", "c"),
		func(ctx context.Context, cx fw.Context) (string, error) {
			cancel() // shutdown arrives during the first client
			return cx.Client().String(), ctx.Err()
		})

	if got := resultClients(res); !slices.Equal(got, []string{"a"}) {
		t.Errorf("results %v, want only a (b and c absent)", got)
	}
	if !res[0].Failed() || !errors.Is(res[0].Err, context.Canceled) {
		t.Errorf("a: %+v", res[0])
	}
}

func TestEachClientWithNoClientsOrACancelledContextStartsNothing(t *testing.T) {
	t.Parallel()
	base := fw.ContextFor(fw.MustClientID("_platform"))
	task := func(context.Context, fw.Context) (int, error) { t.Error("task ran"); return 0, nil }
	if res := fw.EachClient(t.Context(), nil, base, nil, task); len(res) != 0 {
		t.Errorf("results %v", res)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if res := fw.EachClient(ctx, nil, base, clientIDs("a"), task); len(res) != 0 {
		t.Errorf("results %v", res)
	}
}

func TestEachClientRefusesTheZeroContext(t *testing.T) {
	t.Parallel()
	defer func() {
		if recover() == nil {
			t.Error("no panic")
		}
	}()
	fw.EachClient(t.Context(), nil, fw.Context{}, clientIDs("a"), func(context.Context, fw.Context) (int, error) { return 0, nil })
}
