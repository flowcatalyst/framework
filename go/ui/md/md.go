// Package md renders Markdown to sanitised HTML on the server (the docs
// pages, help text). goldmark parses with GFM (tables, strikethrough, task
// lists, autolinks); raw HTML in the source is parsed but dropped by the
// sanitiser, so the output is safe to embed whatever the input holds.
package md

import (
	"bytes"
	"regexp"

	"github.com/microcosm-cc/bluemonday"
	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/extension"
	"github.com/yuin/goldmark/parser"
	"github.com/yuin/goldmark/renderer/html"
)

var (
	converter = goldmark.New(
		goldmark.WithExtensions(extension.GFM),
		goldmark.WithParserOptions(parser.WithAutoHeadingID()),
		// Raw HTML is left in as goldmark's default would escape it; the
		// sanitiser is the policy, so let it see (and strip) the tags.
		goldmark.WithRendererOptions(html.WithUnsafe()),
	)
	policy = newPolicy()
)

func newPolicy() *bluemonday.Policy {
	p := bluemonday.UGCPolicy()
	// Anchors for deep links into a page: headings carry the ids goldmark
	// derives from their text.
	p.AllowAttrs("id").Matching(regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_-]*$`)).OnElements("h1", "h2", "h3", "h4", "h5", "h6")
	// Fenced code: the language class goldmark emits (language-go).
	p.AllowAttrs("class").Matching(regexp.MustCompile(`^language-[A-Za-z0-9_+-]+$`)).OnElements("code")
	// GFM table alignment.
	p.AllowAttrs("style").Matching(regexp.MustCompile(`^text-align:\s?(left|right|center)$`)).OnElements("th", "td")
	// Task list checkboxes are display-only.
	p.AllowAttrs("type").Matching(regexp.MustCompile(`^checkbox$`)).OnElements("input")
	p.AllowAttrs("checked", "disabled").OnElements("input")
	// Links in rendered docs open as ordinary links; http(s), mailto and
	// relative URLs only (UGCPolicy's default), never javascript: or data:.
	p.RequireNoFollowOnLinks(false)
	p.AllowURLSchemes("http", "https", "mailto")
	return p
}

// HTML renders Markdown source to sanitised HTML.
func HTML(src string) (string, error) {
	var buf bytes.Buffer
	if err := converter.Convert([]byte(src), &buf); err != nil {
		return "", err
	}
	return policy.Sanitize(buf.String()), nil
}
