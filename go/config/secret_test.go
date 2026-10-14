package config_test

import (
	"bytes"
	"encoding/json/v2"
	"fmt"
	"log/slog"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/config"
)

const redacted = "Secret[****]"

func TestSecretFormatsRedactedForEveryVerb(t *testing.T) {
	t.Parallel()
	s := config.NewSecret("hunter2")
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x", "%X", "%d", "%t", "%p", "%10s", "%-12v", "%T"} {
		out := fmt.Sprintf(verb, s)
		if strings.Contains(out, "hunter2") || strings.Contains(out, "68756e74") {
			t.Errorf("%s leaked: %q", verb, out)
		}
		if verb != "%T" && verb != "%p" && !strings.Contains(out, redacted) {
			t.Errorf("%s = %q; want it to contain %s", verb, out, redacted)
		}
	}
	if s.String() != redacted || s.GoString() != redacted {
		t.Errorf("String/GoString = %q / %q", s.String(), s.GoString())
	}
	type holder struct {
		Name string
		S    config.Secret
		P    *config.Secret
	}
	out := fmt.Sprintf("%v %+v %#v", holder{"n", s, &s}, holder{"n", s, &s}, holder{"n", s, &s})
	if strings.Contains(out, "hunter2") {
		t.Errorf("struct print leaked: %s", out)
	}
	if got := fmt.Sprint([]config.Secret{s}, map[string]config.Secret{"k": s}); strings.Contains(got, "hunter2") {
		t.Errorf("collection print leaked: %s", got)
	}
}

func TestSecretRedactedInSlog(t *testing.T) {
	t.Parallel()
	var buf bytes.Buffer
	for _, h := range []slog.Handler{slog.NewTextHandler(&buf, nil), slog.NewJSONHandler(&buf, nil)} {
		buf.Reset()
		slog.New(h).Info("x", "password", config.NewSecret("hunter2"), slog.Group("g", slog.Any("s", config.NewSecret("hunter2"))))
		if strings.Contains(buf.String(), "hunter2") || !strings.Contains(buf.String(), redacted) {
			t.Errorf("slog output: %s", buf.String())
		}
	}
}

func TestSecretIsNotSerialisable(t *testing.T) {
	t.Parallel()
	type conf struct {
		Password config.Secret `json:"password"`
	}
	if b, err := json.Marshal(conf{Password: config.NewSecret("hunter2")}); err == nil {
		t.Errorf("json.Marshal succeeded: %s", b)
	}
	if b, err := json.Marshal(config.NewSecret("hunter2")); err == nil {
		t.Errorf("json.Marshal of a Secret succeeded: %s", b)
	}
	s := config.NewSecret("hunter2")
	if _, err := s.MarshalJSON(); err == nil {
		t.Error("MarshalJSON must fail")
	}
	if _, err := s.MarshalText(); err == nil {
		t.Error("MarshalText must fail")
	}
}

func TestSecretComparison(t *testing.T) {
	t.Parallel()
	s := config.NewSecret("hunter2")
	if !s.Matches("hunter2") || s.Matches("hunter3") || s.Matches("") || s.Matches("hunter2 ") {
		t.Error("Matches")
	}
	if !s.Equal(config.NewSecret("hunter2")) || s.Equal(config.NewSecret("x")) || s.Equal(config.Secret{}) {
		t.Error("Equal")
	}
	if s.Reveal() != "hunter2" {
		t.Error("Reveal")
	}
}
