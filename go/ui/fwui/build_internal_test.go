package fwui

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/views"
)

// The flash cookie is Secure unless APP_ENV says local or test (and the request is plain HTTP).
func TestFlashCookieIsSecureOutsideLocalAndTest(t *testing.T) {
	for env, secure := range map[string]bool{"production": true, "staging": true, "": true, "local": false, "test": false} {
		saved := map[string]kit.FlashMessageProps{"saved": {Kind: kit.FlashSuccess, Summary: "Saved."}}
		m, err := build(env, slog.New(slog.DiscardHandler), []Option{WithViews(views.WithFlashes(saved))})
		if err != nil {
			t.Fatal(err)
		}
		rec := httptest.NewRecorder()
		if err := m.Views().SetFlash(rec, httptest.NewRequest("GET", "/", http.NoBody), "saved"); err != nil {
			t.Fatal(err)
		}
		if got := rec.Result().Cookies()[0].Secure; got != secure {
			t.Errorf("APP_ENV=%q over plain HTTP: Secure = %v, want %v", env, got, secure)
		}
	}
}
