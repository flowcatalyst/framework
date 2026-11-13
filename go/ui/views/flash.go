package views

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"

	"github.com/flowcatalyst/framework/go/ui/ds"
	"github.com/flowcatalyst/framework/go/ui/i18n"
	"github.com/flowcatalyst/framework/go/ui/look"
)

// FlashCookie is the cookie that carries a flash message across a redirect (contract section 9).
// It holds a key, never message text: the application maps keys to messages in code
// ([WithFlashes]).
const FlashCookie = "fw_flash"

// flashMaxAge bounds how long a key waits for the page after the redirect, in seconds.
const flashMaxAge = 60

var flashKeyPattern = regexp.MustCompile(`^[a-z0-9-]{1,64}$`)

// ErrInvalidFlashKey is returned for a flash key that is not [a-z0-9-]{1,64}.
var ErrInvalidFlashKey = errors.New("views: a flash key must match [a-z0-9-]{1,64}")

// ErrUnknownFlashKey is returned by SetFlash and SeeOther for a key the application did not
// register: it would render nothing on the next page, so a typo is refused instead of skipped.
var ErrUnknownFlashKey = errors.New("views: unknown flash key")

// flashCookie is the Set-Cookie value: Path=/, HttpOnly, SameSite=Lax, Max-Age (0 clears it), and Secure
// unless the application said plain HTTP is acceptable (local and test) and this request is plain
// HTTP. The value is a validated key or empty, so it needs no quoting. (Written by hand rather than
// with http.Cookie, as the sessions module does.)
func (v *Views) flashCookie(r *http.Request, value string, maxAge int) string {
	cookie := FlashCookie + "=" + value + "; Path=/; Max-Age=" + strconv.Itoa(maxAge) + "; HttpOnly; SameSite=Lax"
	if v.plainCookies && r.TLS == nil {
		return cookie
	}
	return cookie + "; Secure"
}

// SetFlash queues the message registered under key for the next document this browser is sent
// (call it before the 303). The key is a programming constant, never request data; one that is not
// [a-z0-9-]{1,64}, or that was not registered with [WithFlashes], is an error.
func (v *Views) SetFlash(w http.ResponseWriter, r *http.Request, key string) error {
	if !flashKeyPattern.MatchString(key) {
		return ErrInvalidFlashKey
	}
	if _, ok := v.flashes[key]; !ok {
		return fmt.Errorf("views: flash key %q is not registered (WithFlashes): %w", key, ErrUnknownFlashKey)
	}
	w.Header().Add("Set-Cookie", v.flashCookie(r, key, flashMaxAge))
	return nil
}

// SeeOther answers a native form post that succeeded: 303 to a local path (ds.IsLocalPath),
// carrying the flash with this key when it is not empty. A Datastar request cannot follow a 303 the
// way a browser does, so it gets the same outcome as an answer: the flash cookie and a stream with a
// redirect patch (status 200). A path that is not local is an error and nothing is sent.
func (v *Views) SeeOther(w http.ResponseWriter, r *http.Request, path, flashKey string) error {
	if !ds.IsLocalPath(path) {
		return ds.ErrUnsafeRedirect
	}
	if flashKey != "" {
		if err := v.SetFlash(w, r, flashKey); err != nil {
			return err
		}
	}
	if ds.IsDatastar(r) {
		return v.Stream(w, r, func(p *Patches) error { return p.Redirect(path) })
	}
	w.Header().Set("Location", path)
	w.WriteHeader(http.StatusSeeOther)
	return nil
}

// localizeFlash is the registered message with its summary from flash.<key> and its detail from
// flash.<key>.detail when the catalogs have them (contract i18n.md section 4).
func localizeFlash(ctx context.Context, key string, m look.Flash) look.Flash {
	if i18n.Has(ctx, "flash."+key) {
		m.Summary = i18n.T(ctx, "flash."+key)
	}
	if i18n.Has(ctx, "flash."+key+".detail") {
		m.Detail = i18n.T(ctx, "flash."+key+".detail")
	}
	return m
}

// takeFlash returns the message the flash cookie's key names (none for an unknown or malformed
// key) and whether the cookie was present, in which case the caller clears it.
func (v *Views) takeFlash(r *http.Request, ctx context.Context) (msgs []look.Flash, present bool) {
	c, err := r.Cookie(FlashCookie)
	if err != nil {
		return nil, false
	}
	if !flashKeyPattern.MatchString(c.Value) {
		return nil, true
	}
	if m, ok := v.flashes[c.Value]; ok {
		return []look.Flash{localizeFlash(ctx, c.Value, m)}, true
	}
	return nil, true
}

func checkFlashes(m map[string]look.Flash) error {
	for key, msg := range m {
		if !flashKeyPattern.MatchString(key) {
			return fmt.Errorf("views: flash key %q: %w", key, ErrInvalidFlashKey)
		}
		switch msg.Kind {
		case "", look.FlashSuccess, look.FlashInfo, look.FlashWarn, look.FlashError:
		default:
			return fmt.Errorf("views: flash %q has the unknown kind %q", key, string(msg.Kind))
		}
	}
	return nil
}
