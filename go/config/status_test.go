package config_test

import (
	"net/url"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
)

func TestStatusesSayWhereEachValueCameFromAndShowOnlyWhatIsSafe(t *testing.T) {
	t.Parallel()
	d := &declarer{}
	config.Declare(d, config.String("svc.name", "A name.").Required())
	config.Declare(d, config.Duration("svc.timeout", "Timeout.").WithDefault(90*time.Second))
	config.Declare(d, config.Int("svc.unset", "Unset."))
	config.Declare(d, config.SecretValue("svc.key", "A secret."))
	config.Declare(d, config.String("svc.api-token", "Looks like a credential, is text."))
	config.Declare(d, config.URL("svc.endpoint", "An endpoint."))
	config.Declare(d, config.StringList("svc.tags", "Tags."))
	dir := files(t, map[string]string{"application.yaml": "svc:\n  name: orders\n"})
	v, problems := load(t, dir, map[string]string{
		"SVC_KEY": "hunter2", "SVC_API_TOKEN": "tok-123", "SVC_ENDPOINT": "https://u:p@host.example:8443/v1?token=abc#frag", "SVC_TAGS": "a,b",
	}, d.decls...)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	type want struct {
		state    config.State
		source   string
		value    string
		hasValue bool
		withheld bool
	}
	wants := map[string]want{
		"svc.name":      {config.StateSet, dir + "/application.yaml", "orders", true, false},
		"svc.timeout":   {config.StateDefault, "default", "PT1M30S", true, false},
		"svc.unset":     {config.StateUnset, "not set", "", false, false},
		"svc.key":       {config.StateSet, "environment variable SVC_KEY", "", false, true},
		"svc.api-token": {config.StateSet, "environment variable SVC_API_TOKEN", "", false, true},
		"svc.endpoint":  {config.StateSet, "environment variable SVC_ENDPOINT", "https://host.example:8443/v1", true, false},
		"svc.tags":      {config.StateSet, "environment variable SVC_TAGS", "a,b", true, false},
	}
	statuses := v.Statuses()
	if len(statuses) != len(wants) {
		t.Fatalf("got %d statuses", len(statuses))
	}
	for i, s := range statuses {
		w, ok := wants[s.Key]
		if !ok {
			t.Fatalf("unexpected %s", s.Key)
		}
		if s.Module != "svc" || s.State != w.state || s.Value != w.value || s.HasValue != w.hasValue || s.Withheld != w.withheld {
			t.Errorf("%s: got %+v, want %+v", s.Key, s, w)
		}
		if s.Source != w.source && (s.State != config.StateSet || s.Key != "svc.name") {
			t.Errorf("%s: source %q, want %q", s.Key, s.Source, w.source)
		}
		if i == 0 && s.Key != "svc.name" {
			t.Errorf("not in declaration order: %s first", s.Key)
		}
	}
	if !statuses[0].Required || statuses[1].Required || statuses[3].Secret != true || statuses[0].Description != "A name." {
		t.Errorf("flags: %+v", statuses[:4])
	}
	var nilValues *config.Values
	if len(nilValues.Statuses()) != 0 {
		t.Error("a nil Values has statuses")
	}
}

func TestStatusValueRendering(t *testing.T) {
	t.Parallel()
	u, _ := url.Parse("jdbc:postgresql://user:pw@db.example:5432/app?password=x")
	d := &declarer{}
	config.Declare(d, config.URL("svc.jdbc", "A JDBC URL."))
	for _, c := range []struct {
		d    time.Duration
		text string
	}{{0, "PT0S"}, {30 * time.Second, "PT30S"}, {90 * time.Minute, "PT1H30M"}, {1500 * time.Millisecond, "PT1.5S"}, {26 * time.Hour, "PT26H"}} {
		dd := &declarer{}
		config.Declare(dd, config.Duration("svc.d", "d.").WithDefault(c.d))
		v, problems := load(t, files(t, nil), nil, dd.decls...)
		if len(problems) > 0 || v == nil {
			t.Fatal(problems)
		}
		if got := v.Statuses()[0].Value; got != c.text {
			t.Errorf("%v: got %s, want %s", c.d, got, c.text)
		}
	}
	v, problems := load(t, files(t, nil), map[string]string{"SVC_JDBC": u.String()}, d.decls...)
	if len(problems) > 0 {
		t.Fatal(problems)
	}
	if got := v.Statuses()[0].Value; got != "jdbc:postgresql://db.example:5432/app" {
		t.Errorf("got %s", got)
	}
}
