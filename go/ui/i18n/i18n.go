// Package i18n is the language mechanism of the server-rendered UI kit:
// flat JSON catalogs (message key to message), layered lookup with fallback,
// named placeholders, CLDR plural categories for en, af and zu, and the
// resolution of one locale per request. Standard library only. Contract:
// docs/contracts/i18n.md.
//
//	h := i18n.Middleware(i18n.Config{Supported: []string{"en", "af"}})(next)
//	// in a handler or a templ component, with the request's context:
//	i18n.T(ctx, "kit.list.next")
//	i18n.T(ctx, "kit.list.rows", i18n.Args{"count": 3})
//
// The kit's own messages are embedded ([Kit]); an application layers its own
// catalogs on top ([Bundle.With], [LoadFS]).
package i18n

import (
	"context"
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"maps"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
)

// DefaultLocale is the source language, the default and the fallback of every message.
const DefaultLocale = "en"

// Args are the named arguments of a message: the values of its placeholders,
// and, as "count", the number a plural message selects its category from.
type Args map[string]any

// Message is one catalog entry: a text, or a plural object keyed by CLDR
// plural category.
type Message struct {
	// Text is the message when it is not plural.
	Text string
	// Plural holds the category texts of a plural message (nil for a text).
	Plural map[string]string
}

// Catalog is one language's messages, keyed by message key.
type Catalog map[string]Message

var (
	keyPattern  = regexp.MustCompile(`^[a-z0-9]+(\.[A-Za-z0-9_-]+)+$`)
	namePattern = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9]*$`)
	categories  = []string{"zero", "one", "two", "few", "many", "other"}
)

// ParseCatalog reads a catalog file: a JSON object of key to message, a
// message being a string or a plural object (categories zero, one, two, few,
// many, other; other is required). A key that is not
// [a-z0-9]+(\.[A-Za-z0-9_-]+)+ is an error.
func ParseCatalog(data []byte) (Catalog, error) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("i18n: catalog is not a JSON object: %w", err)
	}
	c := make(Catalog, len(raw))
	for _, key := range slices.Sorted(maps.Keys(raw)) {
		if !keyPattern.MatchString(key) {
			return nil, fmt.Errorf("i18n: invalid message key %q", key)
		}
		v := raw[key]
		var text string
		if err := json.Unmarshal(v, &text); err == nil {
			c[key] = Message{Text: text}
			continue
		}
		var plural map[string]string
		if err := json.Unmarshal(v, &plural); err != nil {
			return nil, fmt.Errorf("i18n: message %q must be a string or a plural object of strings", key)
		}
		for cat := range plural {
			if !slices.Contains(categories, cat) {
				return nil, fmt.Errorf("i18n: message %q: %q is not a plural category", key, cat)
			}
		}
		if _, ok := plural["other"]; !ok {
			return nil, fmt.Errorf("i18n: plural message %q has no \"other\"", key)
		}
		c[key] = Message{Plural: plural}
	}
	return c, nil
}

// Layer is one source of catalogs: language tag to catalog (the kit's
// messages are one layer, an application's i18n/ directory another).
type Layer map[string]Catalog

// LoadFS reads dir/<tag>.json for every such file of fsys (an application's
// embedded "i18n" directory, for instance).
func LoadFS(fsys fs.FS, dir string) (Layer, error) {
	entries, err := fs.ReadDir(fsys, dir)
	if err != nil {
		return nil, fmt.Errorf("i18n: %w", err)
	}
	l := Layer{}
	for _, e := range entries {
		tag, ok := strings.CutSuffix(e.Name(), ".json")
		if !ok || e.IsDir() {
			continue
		}
		data, err := fs.ReadFile(fsys, dir+"/"+e.Name())
		if err != nil {
			return nil, fmt.Errorf("i18n: %w", err)
		}
		c, err := ParseCatalog(data)
		if err != nil {
			return nil, fmt.Errorf("i18n: %s: %w", e.Name(), err)
		}
		l[NormalizeTag(tag)] = c
	}
	return l, nil
}

//go:embed messages/*.json
var kitFS embed.FS

var (
	kitOnce   sync.Once
	kitLayer  Layer
	kitErr    error
	kitBundle *Bundle
)

// KitCatalogs are the kit's catalogs, one per language, as embedded
// (ui-kit/messages/<tag>.json, shared with the Java kit).
func KitCatalogs() (Layer, error) {
	kitOnce.Do(func() {
		kitLayer, kitErr = LoadFS(kitFS, "messages")
		if kitErr == nil {
			kitBundle = NewBundle(kitLayer)
		}
	})
	return kitLayer, kitErr
}

// Kit is the bundle of the kit's own messages. It panics if the embedded
// catalogs do not parse (a test guards that).
func Kit() *Bundle {
	if _, err := KitCatalogs(); err != nil {
		panic(err)
	}
	b := kitBundle
	if b == nil {
		panic("i18n: the kit bundle is not loaded")
	}
	return b
}

// Bundle is the layered catalogs of an application: the kit's first, the
// application's on top.
type Bundle struct {
	layers  []Layer // base first
	missing func(locale, key string)
}

// NewBundle layers catalogs, base first: later layers win.
func NewBundle(layers ...Layer) *Bundle {
	return &Bundle{layers: slices.Clone(layers)}
}

// With returns a bundle with the layer on top of this one's.
func (b *Bundle) With(l Layer) *Bundle {
	nb := *b
	nb.layers = append(slices.Clone(b.layers), l)
	return &nb
}

// OnMissing returns a bundle that calls fn for every lookup that finds the
// key in no catalog, not even English (the lookup then renders the key). Tests
// use it to fail on a raw key.
func (b *Bundle) OnMissing(fn func(locale, key string)) *Bundle {
	nb := *b
	nb.missing = fn
	return &nb
}

// Languages are the tags the bundle has a catalog for, sorted.
func (b *Bundle) Languages() []string {
	set := map[string]bool{}
	for _, l := range b.layers {
		for tag := range l {
			set[tag] = true
		}
	}
	return slices.Sorted(maps.Keys(set))
}

// steps are the catalogs tried for a locale, in order: the tag, its language,
// then English (each once).
func steps(locale string) []string {
	tag := NormalizeTag(locale)
	out := make([]string, 0, 3)
	if tag != "" {
		out = append(out, tag)
		if lang := Language(tag); lang != tag {
			out = append(out, lang)
		}
	}
	if !slices.Contains(out, DefaultLocale) {
		out = append(out, DefaultLocale)
	}
	return out
}

// find returns the message and the language of the catalog it came from. Per
// key: the locale's tag, its language, then English; within one tag the
// topmost layer wins.
func (b *Bundle) find(locale, key string) (Message, string, bool) {
	for _, tag := range steps(locale) {
		for _, layer := range slices.Backward(b.layers) {
			if m, ok := layer[tag][key]; ok {
				return m, Language(tag), true
			}
		}
	}
	return Message{}, "", false
}

// Has reports whether some catalog has the key for the locale or a fallback.
func (b *Bundle) Has(locale, key string) bool {
	_, _, ok := b.find(locale, key)
	return ok
}

// Lookup is the message for the key in the locale, with the arguments
// applied; ok is false when no catalog has the key (the text is then the key).
func (b *Bundle) Lookup(locale, key string, args Args) (string, bool) {
	return b.lookup(locale, key, args, false)
}

// lookup is [Bundle.Lookup]; strict also refuses a text whose placeholders the
// arguments do not all supply (the text is then the key and ok is false).
func (b *Bundle) lookup(locale, key string, args Args, strict bool) (string, bool) {
	m, lang, ok := b.find(locale, key)
	if !ok {
		if b.missing != nil {
			b.missing(locale, key)
		}
		return key, false
	}
	text := m.Text
	if m.Plural != nil {
		cat := "other"
		if v, has := args["count"]; has {
			cat = PluralCategory(lang, argText(v))
		}
		if t, has := m.Plural[cat]; has {
			text = t
		} else {
			text = m.Plural["other"]
		}
	}
	if strict {
		for _, name := range Placeholders(text) {
			if _, has := args[name]; !has {
				return key, false
			}
		}
	}
	return Format(text, args), true
}

// Message is [Bundle.Lookup] without the flag: the key when nothing has it.
func (b *Bundle) Message(locale, key string, args ...Args) string {
	var a Args
	if len(args) > 0 {
		a = args[0]
	}
	s, _ := b.Lookup(locale, key, a)
	return s
}

// argText is the text of an argument: integers and decimals as plain decimal
// text, strings and Stringers as they are.
func argText(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	case float32:
		return strconv.FormatFloat(float64(x), 'f', -1, 32)
	case fmt.Stringer:
		return x.String()
	default:
		return fmt.Sprint(v)
	}
}

// Format replaces {name} with the argument's text; an argument that is not
// given leaves the placeholder as it is; "{{" and "}}" are a literal brace.
func Format(text string, args Args) string {
	if !strings.ContainsAny(text, "{}") {
		return text
	}
	var sb strings.Builder
	for i := 0; i < len(text); {
		c := text[i]
		switch {
		case c == '{' && strings.HasPrefix(text[i:], "{{"):
			sb.WriteByte('{')
			i += 2
		case c == '}' && strings.HasPrefix(text[i:], "}}"):
			sb.WriteByte('}')
			i += 2
		case c == '{':
			end := strings.IndexByte(text[i:], '}')
			if end > 0 && namePattern.MatchString(text[i+1:i+end]) {
				name := text[i+1 : i+end]
				if v, ok := args[name]; ok {
					sb.WriteString(argText(v))
				} else {
					sb.WriteString(text[i : i+end+1])
				}
				i += end + 1
				continue
			}
			sb.WriteByte(c)
			i++
		default:
			sb.WriteByte(c)
			i++
		}
	}
	return sb.String()
}

// Placeholders are the names of the placeholders of a message text, sorted,
// without repeats ("{{" and "}}" are not placeholders).
func Placeholders(text string) []string {
	set := map[string]bool{}
	for i := 0; i < len(text); {
		switch {
		case strings.HasPrefix(text[i:], "{{"), strings.HasPrefix(text[i:], "}}"):
			i += 2
		case text[i] == '{':
			end := strings.IndexByte(text[i:], '}')
			if end > 0 && namePattern.MatchString(text[i+1:i+end]) {
				set[text[i+1:i+end]] = true
				i += end + 1
				continue
			}
			i++
		default:
			i++
		}
	}
	return slices.Sorted(maps.Keys(set))
}

// PluralCategory is the CLDR cardinal category of the number (plain decimal
// text) for the language: en (one: i = 1 and v = 0), af (one: n = 1), zu (one:
// i = 0 or n = 1); any other language uses en's rule. Text that is not a
// decimal number is "other".
func PluralCategory(lang, count string) string {
	i, frac, ok := operands(count)
	if !ok {
		return "other"
	}
	isOne := i == "1" && allZero(frac) // n = 1
	switch strings.ToLower(lang) {
	case "af":
		if isOne {
			return "one"
		}
	case "zu":
		if i == "0" || isOne {
			return "one"
		}
	default: // en
		if i == "1" && frac == "" {
			return "one"
		}
	}
	return "other"
}

var decimalPattern = regexp.MustCompile(`^[+-]?([0-9]+)(?:\.([0-9]+))?$`)

// operands splits plain decimal text into the integer digits (without leading
// zeros) and the visible fraction digits.
func operands(s string) (integer, frac string, ok bool) {
	m := decimalPattern.FindStringSubmatch(strings.TrimSpace(s))
	if m == nil {
		return "", "", false
	}
	integer = strings.TrimLeft(m[1], "0")
	if integer == "" {
		integer = "0"
	}
	return integer, m[2], true
}

func allZero(s string) bool { return strings.Trim(s, "0") == "" }

// NormalizeTag writes a BCP 47 tag in canonical case (language lower, script
// title, region upper); "_" is read as "-". A tag that is not
// language(-subtag)* of letters and digits is returned as "".
func NormalizeTag(tag string) string {
	tag = strings.ReplaceAll(strings.TrimSpace(tag), "_", "-")
	if tag == "" {
		return ""
	}
	parts := strings.Split(tag, "-")
	for i, p := range parts {
		if p == "" || len(p) > 8 || strings.ContainsFunc(p, func(r rune) bool { return !isAlnum(r) }) {
			return ""
		}
		if i == 0 {
			if len(p) < 2 || len(p) > 3 || strings.ContainsFunc(p, func(r rune) bool { return r >= '0' && r <= '9' }) {
				return ""
			}
			parts[i] = strings.ToLower(p)
			continue
		}
		switch {
		case len(p) == 2 && !strings.ContainsFunc(p, func(r rune) bool { return r >= '0' && r <= '9' }):
			parts[i] = strings.ToUpper(p)
		case len(p) == 4 && !strings.ContainsFunc(p, func(r rune) bool { return r >= '0' && r <= '9' }):
			parts[i] = strings.ToUpper(p[:1]) + strings.ToLower(p[1:])
		default:
			parts[i] = strings.ToLower(p)
		}
	}
	return strings.Join(parts, "-")
}

func isAlnum(r rune) bool {
	return r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9'
}

// Language is the language subtag of a tag ("af" for "af-ZA").
func Language(tag string) string {
	lang, _, _ := strings.Cut(tag, "-")
	return lang
}

type ctxKey int

const (
	localeKey ctxKey = iota
	bundleKey
)

// WithLocale returns a context carrying the request's locale.
func WithLocale(ctx context.Context, tag string) context.Context {
	return context.WithValue(ctx, localeKey, NormalizeTag(tag))
}

// WithBundle returns a context carrying the bundle [T] reads from.
func WithBundle(ctx context.Context, b *Bundle) context.Context {
	return context.WithValue(ctx, bundleKey, b)
}

// Resolved reports whether a locale was stored in the context ([Middleware] or [WithLocale]).
func Resolved(ctx context.Context) bool {
	tag, ok := ctx.Value(localeKey).(string)
	return ok && tag != ""
}

// FromContext is the request's locale: the one [Middleware] resolved, or "en"
// for a context that carries none.
func FromContext(ctx context.Context) string {
	if tag, ok := ctx.Value(localeKey).(string); ok && tag != "" {
		return tag
	}
	return DefaultLocale
}

// BundleFromContext is the bundle of the context: the application's (set by
// [Middleware] or [WithBundle]), or the kit's own.
func BundleFromContext(ctx context.Context) *Bundle {
	if b, ok := ctx.Value(bundleKey).(*Bundle); ok && b != nil {
		return b
	}
	return Kit()
}

// T is the message for the key in the context's locale.
func T(ctx context.Context, key string, args ...Args) string {
	return BundleFromContext(ctx).Message(FromContext(ctx), key, args...)
}

// Validation is the text of a field error in the context's locale: the message
// validation.<code> with the error's parameters as arguments, or message (the
// error's own English text) when no catalog has that key or the text needs a
// parameter the error did not carry (contract section 4).
func Validation(ctx context.Context, code string, params Args, message string) string {
	if text, ok := BundleFromContext(ctx).lookup(FromContext(ctx), "validation."+code, params, true); ok {
		return text
	}
	return message
}

// Has reports whether the context's bundle has a message for the key
// (the locale, its language or English).
func Has(ctx context.Context, key string) bool {
	return BundleFromContext(ctx).Has(FromContext(ctx), key)
}
