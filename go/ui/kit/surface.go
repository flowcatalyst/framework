package kit

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/a-h/templ"
)

// renderSlot renders a slot (the default children or a named slot) to text so
// a component can leave out the region around an empty one (card.md). The text
// is markup that templ components wrote, so the caller writes it with [Raw].
func renderSlot(ctx context.Context, c templ.Component) (string, error) {
	if c == nil {
		return "", nil
	}
	var b strings.Builder
	if err := c.Render(ctx, &b); err != nil {
		return "", err
	}
	if strings.TrimSpace(b.String()) == "" {
		return "", nil
	}
	return b.String(), nil
}

// num writes a number as decimal text, without an exponent (progress.md).
func num(f float64) string { return strconv.FormatFloat(f, 'f', -1, 64) }

// JSONValue encodes a Go value for [JSONBlockProps].Value: struct and slice
// order is kept, and <, > and & are not escaped (json-block.md; the HTML escape
// is the block's). Map keys come out sorted, as encoding/json writes them; pass
// a json.RawMessage to keep a given order. It panics on a value that cannot be
// encoded.
func JSONValue(v any) json.RawMessage {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		panic(fmt.Sprintf("kit: json-block value: %v", err))
	}
	return bytes.TrimSpace(b.Bytes())
}

// prettyJSON formats a raw JSON value as json-block.md says: 2-space indent,
// `"key": value`, one member per line, `{}` and `[]` when empty, keys in the
// order given. The text is only re-spaced, never decoded, so key order, number
// text and string escapes stay as written. An absent value is null; invalid
// JSON panics (a programming error).
func prettyJSON(raw json.RawMessage) string {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 {
		return "null"
	}
	var out bytes.Buffer
	if err := json.Indent(&out, raw, "", "  "); err != nil {
		panic(fmt.Sprintf("kit: json-block value is not JSON: %v", err))
	}
	return out.String()
}

// cardLayout is a Go component because a templ component cannot look at its
// own children: this one renders the default slot and the footer to text, to
// know whether their regions are empty, then draws the frame.
func cardLayout(p CardProps) templ.Component {
	return templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		content, err := renderSlot(ctx, templ.GetChildren(ctx))
		if err != nil {
			return err
		}
		footer, err := renderSlot(ctx, p.Footer)
		if err != nil {
			return err
		}
		return cardFrame(p, content, footer).Render(ctx, w)
	})
}

// kbdKeys refuses an empty key list: a key hint with no key is a programming
// error (kbd.md Props), not an empty render.
func kbdKeys(keys []string) []string {
	if len(keys) == 0 {
		panic("kit: kbd keys must not be empty (kbd.md Props)")
	}
	return keys
}
