package flowcatalyst_test

import (
	"errors"
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
)

func problemsFor(err error) []fw.Problem {
	var se *fw.StartupError
	if !errors.As(err, &se) {
		return nil
	}
	return se.Problems
}

func problemKeyed(t *testing.T, err error, key string) fw.Problem {
	t.Helper()
	for _, p := range problemsFor(err) {
		if p.Key == key {
			return p
		}
	}
	t.Fatalf("no problem for %q in %v", key, problemTexts(err))
	return fw.Problem{}
}

func TestValidDefinitionsFromSeveralModulesAreCollectedInOrder(t *testing.T) {
	r := startRig(t, rigOptions{define: func(d *flowcatalyst.Declared) {
		d.Definitions("sales").EventType("orders:sales:order:placed", "Order placed", "d").
			Permission("orders:sales:order:read", "Read orders").Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read"))
		d.Definitions("billing").EventType("orders:billing:invoice:issued", "Invoice issued", "").
			Connection("main", "Main").Docs("guide", "# Guide")
	}})
	c := r.fc.Catalog()
	var codes []string
	for _, e := range c.EventTypes() {
		codes = append(codes, e.Code)
	}
	if !slices.Equal(codes, []string{"orders:sales:order:placed", "orders:billing:invoice:issued"}) {
		t.Fatalf("event types %v", codes)
	}
	if len(c.Permissions()) != 1 || len(c.Roles()) != 1 || c.Roles()[0].Name != "clerk" || len(c.Connections()) != 1 || len(c.Docs()) != 1 || c.Docs()[0].Slug != "guide" {
		t.Fatalf("catalog %+v", c)
	}
}

func TestATypedEventTypeConstantIsDeclared(t *testing.T) {
	placed := event.MustType("orders:sales:order:placed")
	r := startRig(t, rigOptions{define: func(d *flowcatalyst.Declared) { d.Definitions("sales").EventTypeOf(placed, "Order placed", "") }})
	if got := r.fc.Catalog().EventTypes(); len(got) != 1 || got[0].Code != "orders:sales:order:placed" {
		t.Fatalf("%+v", got)
	}
}

func TestAnEventTypeOfAnotherApplicationNamesTheModuleAndTheCode(t *testing.T) {
	_, err := tryRig(t, rigOptions{define: func(d *flowcatalyst.Declared) {
		d.Definitions("sales").EventType("billing:sales:order:placed", "X", "")
	}})
	p := problemKeyed(t, err, "billing:sales:order:placed")
	if p.Module != "sales" || !strings.Contains(p.Message, "must start with the application 'orders'") {
		t.Fatalf("%+v", p)
	}
}

func TestEveryDefinitionProblemIsReportedTogether(t *testing.T) {
	_, err := tryRig(t, rigOptions{define: func(d *flowcatalyst.Declared) {
		d.Definitions("sales").
			EventType("orders:sales:order", "Three parts", "").
			EventType("orders:Sales:order:placed", "Upper case", "").
			Permission("billing:x:y:z", "wrong app").
			Role(flowcatalyst.NewRole("orders:clerk")).
			Role(flowcatalyst.NewRole("clerk", "not-a-permission")).
			Subscription(flowcatalyst.NewSubscription("hook", "Hook", "/relative", "orders:sales:order:placed")).
			Subscription(flowcatalyst.NewSubscription("empty", "Empty", "https://x.example/h")).
			ScheduledJob(flowcatalyst.NewScheduledJob("five", "Five fields", "0 2 * * *")).
			Process(flowcatalyst.Process{Code: "orders:checkout", Name: "Two parts"}).
			Docs("Not A Slug", "text").
			DispatchPool(flowcatalyst.DispatchPool{Code: "pool", Name: "Pool", RateLimit: -1})
		d.Definitions("billing").Connection("main", "Main").Connection("main", "Again")
	}})
	for key, want := range map[string]string{
		"orders:sales:order": "{application}:{domain}:{aggregate}:{action}", "billing:x:y:z": "must start with the application 'orders'",
		"orders:clerk": "must not include the application prefix", "not-a-permission": "permission of role 'clerk'",
		"hook": "subscription-target-base-url", "empty": "at least one event type", "five": "6 fields",
		"orders:checkout": "{application}:{subdomain}:{process}", "Not A Slug": "kebab-case", "pool": "rateLimit must be positive",
	} {
		if p := problemKeyed(t, err, key); !strings.Contains(p.Message, want) {
			t.Errorf("%s: %q lacks %q", key, p.Message, want)
		}
	}
	if p := problemKeyed(t, err, "orders:Sales:order:placed"); p.Module != "sales" {
		t.Errorf("module %q", p.Module)
	}
	dup := problemKeyed(t, err, "main")
	if dup.Module != "billing" || !strings.Contains(dup.Message, "duplicate connection") || !strings.Contains(dup.Message, "module 'billing'") {
		t.Errorf("duplicate %+v", dup)
	}
	if n := len(problemsFor(err)); n != 12 {
		t.Errorf("%d problems, want 12: %v", n, problemTexts(err))
	}
}

func TestADuplicateAcrossModulesNamesBothModules(t *testing.T) {
	_, err := tryRig(t, rigOptions{define: func(d *flowcatalyst.Declared) {
		d.Definitions("a").EventType("orders:x:y:z", "One", "")
		d.Definitions("b").EventType("orders:x:y:z", "Two", "")
	}})
	ps := problemsFor(err)
	if len(ps) != 1 || ps[0].Module != "b" || !strings.Contains(ps[0].Message, "already declared by module 'a'") {
		t.Fatalf("%v", problemTexts(err))
	}
}

func TestAPathTargetIsAcceptedWhenABaseURLIsConfigured(t *testing.T) {
	r := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_SUBSCRIPTION_TARGET_BASE_URL": "https://orders.example.com/"}, define: func(d *flowcatalyst.Declared) {
		d.Definitions("sales").Subscription(flowcatalyst.NewSubscription("hook", "Hook", "/hooks/placed", "orders:sales:order:placed"))
	}})
	if len(r.fc.Catalog().Subscriptions()) != 1 {
		t.Fatal("subscription missing")
	}
	if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err != nil {
		t.Fatal(err)
	}
	sub, _ := r.platform.syncOf("subscriptions")
	subs := sub.json(t)["subscriptions"].([]any)
	if target := subs[0].(map[string]any)["target"]; target != "https://orders.example.com/hooks/placed" {
		t.Fatalf("target %v: a path resolves against the base URL without a doubled slash", target)
	}
}

func TestEveryOtherRuleOfASubscriptionAJobAndAPool(t *testing.T) {
	bad := func(add func(d *flowcatalyst.Definitions), key, msg string) {
		t.Helper()
		_, err := tryRig(t, rigOptions{define: func(d *flowcatalyst.Declared) { add(d.Definitions("m")) }})
		if p := problemKeyed(t, err, key); !strings.Contains(p.Message, msg) {
			t.Errorf("%s: %q lacks %q", key, p.Message, msg)
		}
	}
	sub := flowcatalyst.NewSubscription("s", "S", "https://x.example/h", "orders:a:b:c")
	bad(func(d *flowcatalyst.Definitions) {
		d.Subscription(flowcatalyst.NewSubscription("S", "S", "https://x.example", "orders:a:b:c"))
	}, "S", "subscription code must match")
	bad(func(d *flowcatalyst.Definitions) {
		d.Subscription(flowcatalyst.NewSubscription("s", " ", "https://x.example", "orders:a:b:c"))
	}, "s", "name is blank")
	bad(func(d *flowcatalyst.Definitions) {
		d.Subscription(flowcatalyst.NewSubscription("s", "S", " ", "orders:a:b:c"))
	}, "s", "target is blank")
	bad(func(d *flowcatalyst.Definitions) {
		d.Subscription(flowcatalyst.NewSubscription("s", "S", "ftp://x", "orders:a:b:c"))
	}, "s", "absolute http(s) URL or a path")
	bad(func(d *flowcatalyst.Definitions) {
		d.Subscription(flowcatalyst.NewSubscription("s", "S", "https://x.example", "bad"))
	}, "bad", "event type of subscription 's'")
	bad(func(d *flowcatalyst.Definitions) { d.Subscription(sub.ViaConnection("Bad")) }, "Bad", "connection of subscription 's'")
	bad(func(d *flowcatalyst.Definitions) { d.Subscription(sub.InPool("Bad")) }, "Bad", "dispatch pool of subscription 's'")
	bad(func(d *flowcatalyst.Definitions) { s := sub; s.SharedConnection = true; d.Subscription(s) }, "s", "sharedConnection needs a connection code")
	bad(func(d *flowcatalyst.Definitions) { s := sub; s.MaxRetries = -1; d.Subscription(s) }, "s", "maxRetries must be positive")
	bad(func(d *flowcatalyst.Definitions) { s := sub; s.TimeoutSeconds = -1; d.Subscription(s) }, "s", "timeoutSeconds must be positive")
	bad(func(d *flowcatalyst.Definitions) { d.Subscription(sub.WithMode("SOMETIMES")) }, "s", "mode must be")
	bad(func(d *flowcatalyst.Definitions) { d.ScheduledJob(flowcatalyst.NewScheduledJob("j", "J")) }, "j", "at least one cron")
	bad(func(d *flowcatalyst.Definitions) {
		j := flowcatalyst.NewScheduledJob("j", "J", "0 0 2 * * *")
		j.TargetURL = "/relative"
		d.ScheduledJob(j)
	}, "j", "targetUrl must be an absolute")
	bad(func(d *flowcatalyst.Definitions) {
		j := flowcatalyst.NewScheduledJob("j", "J", "0 0 2 * * *")
		j.DeliveryMaxAttempts = -2
		d.ScheduledJob(j)
	}, "j", "deliveryMaxAttempts must be positive")
	bad(func(d *flowcatalyst.Definitions) { d.Process(flowcatalyst.Process{Code: "billing:a:b", Name: "P"}) }, "billing:a:b", "must start with the application 'orders'")
	bad(func(d *flowcatalyst.Definitions) { d.Docs("guide", "  ") }, "guide", "content is blank")
	bad(func(d *flowcatalyst.Definitions) {
		d.DispatchPool(flowcatalyst.DispatchPool{Code: "p", Name: "P", Concurrency: -1})
	}, "p", "concurrency must be positive")
	bad(func(d *flowcatalyst.Definitions) { d.Role(flowcatalyst.NewRole("Bad")) }, "Bad", "role name must match")
	bad(func(d *flowcatalyst.Definitions) { d.Permission("orders:Bad:x:y", "") }, "orders:Bad:x:y", "permission code must be")
}

func TestADisabledModuleSkipsValidationAndTheCatalogIsEmpty(t *testing.T) {
	r := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_ENABLED": "false"}, define: func(d *flowcatalyst.Declared) {
		d.Definitions("sales").EventType("garbage", "X", "")
	}})
	if len(r.fc.Catalog().EventTypes()) != 0 {
		t.Fatal("the catalog is not empty")
	}
}

func TestCatalogGettersReturnCopies(t *testing.T) {
	r := startRig(t, rigOptions{define: func(d *flowcatalyst.Declared) { d.Definitions("sales").EventType("orders:a:b:c", "X", "") }})
	got := r.fc.Catalog().EventTypes()
	got[0].Name = "changed"
	if r.fc.Catalog().EventTypes()[0].Name != "X" {
		t.Fatal("the catalog was changed through a getter")
	}
}

func TestARoleKeepsItsPermissionsAsDeclared(t *testing.T) {
	if got := flowcatalyst.NewRole("r", "a:b:c:d", "a:b:c:e").Permissions; !slices.Equal(got, []string{"a:b:c:d", "a:b:c:e"}) {
		t.Fatalf("%v", got)
	}
}

func TestTheJobBuildersSetEachOptionalMemberAndLeaveTheOriginalAlone(t *testing.T) {
	base := flowcatalyst.NewScheduledJob("j", "J", "0 0 2 * * *")
	j := base.WithPayload(map[string]any{"a": 1}).Targeting("http://h/x").WithTimeout(30).WithDeliveryAttempts(5).Tracking(true).InZone("Europe/London").AllowingConcurrent(true)
	if j.Payload == nil || j.TargetURL != "http://h/x" || j.TimeoutSeconds != 30 || j.DeliveryMaxAttempts != 5 || !j.TracksCompletion || j.Timezone != "Europe/London" || !j.Concurrent {
		t.Errorf("%+v", j)
	}
	if base.Payload != nil || base.TargetURL != "" || base.TimeoutSeconds != 0 || base.DeliveryMaxAttempts != 0 {
		t.Errorf("the original changed: %+v", base)
	}
}
