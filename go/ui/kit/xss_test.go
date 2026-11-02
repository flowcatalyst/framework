package kit

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

// The XSS sweep (contract server-rendered-ui.md section 13), ported from Java's
// XssSweepTest: for every fixture and every string value in its props, one at
// a time, put a payload there and render. Either the component refuses it (a
// validated id, signal, path or variant: a panic naming the contract) or the
// output holds no more script or img elements and on* or stray attributes than
// the fixture's own golden does: the payload came out as text or as an
// attribute value. A template that writes a prop unescaped fails here
// whichever prop it is.

const xssPayload = `"'></textarea></title></style></select><script>alert(1)</script><img src=x onerror=alert(2)><b x='`

// step is one step of the path to a string in the props: an object key or an
// array index.
type step struct {
	key   string
	index int
	array bool
}

type leaf []step

func collect(node any, path leaf, out *[]leaf) {
	switch v := node.(type) {
	case map[string]any:
		for k, c := range v {
			collect(c, append(append(leaf{}, path...), step{key: k}), out)
		}
	case []any:
		for i, c := range v {
			collect(c, append(append(leaf{}, path...), step{index: i, array: true}), out)
		}
	case string:
		*out = append(*out, path)
	}
}

// setLeaf replaces the string at path (collected from this very tree).
func setLeaf(root any, path leaf, value string) {
	cur := root
	for i, s := range path {
		last := i == len(path)-1
		if s.array {
			arr, _ := cur.([]any)
			if last {
				arr[s.index] = value
				return
			}
			cur = arr[s.index]
			continue
		}
		obj, _ := cur.(map[string]any)
		if last {
			obj[s.key] = value
			return
		}
		cur = obj[s.key]
	}
}

// renderProps renders the fixture's component with other props.
func renderProps(t *testing.T, render renderer, f *fixture, props []byte) (out string, err error) {
	t.Helper()
	g := &fixture{Props: props, Slots: f.Slots, used: map[string]bool{}}
	c, err := render(g)
	if err != nil {
		return "", err
	}
	var sb strings.Builder
	if err := c.Render(t.Context(), &sb); err != nil {
		return "", err
	}
	return sb.String(), nil
}

func TestAPayloadInAnyStringPropIsRefusedOrNeutralised(t *testing.T) {
	root := fixturesDir(t)
	dirs, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	var rendered, refused, decodeRefused int
	var components []string
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		component := d.Name()
		render, ok := renderers[component]
		if !ok {
			continue // TestConformance reports the missing renderer
		}
		components = append(components, component)
		files, _ := filepath.Glob(filepath.Join(root, component, "*.json"))
		for _, jsonPath := range files {
			raw, err := os.ReadFile(jsonPath)
			if err != nil {
				t.Fatal(err)
			}
			f := &fixture{used: map[string]bool{}}
			if err := json.Unmarshal(raw, f); err != nil {
				t.Fatalf("%s: %v", jsonPath, err)
			}
			if len(f.Props) == 0 {
				continue
			}
			var original any
			if err := json.Unmarshal(f.Props, &original); err != nil {
				t.Fatalf("%s: %v", jsonPath, err)
			}
			// trusted slot markup (the golden's own links with handlers) is the baseline
			base, err := renderProps(t, render, f, f.Props)
			if err != nil {
				t.Fatalf("%s: %v", jsonPath, err)
			}
			images := component != "markdown" // markdown.md keeps <img> elements and filters their attributes
			baseline := dangerOf(base, t, images)
			var leaves []leaf
			collect(original, nil, &leaves)
			for _, lf := range leaves {
				var copyTree any
				if err := json.Unmarshal(f.Props, &copyTree); err != nil {
					t.Fatal(err)
				}
				setLeaf(copyTree, lf, xssPayload)
				props, err := json.Marshal(copyTree)
				if err != nil {
					t.Fatal(err)
				}
				where := fmt.Sprintf("%s/%s %+v", component, filepath.Base(jsonPath), lf)
				out, refusal := attempt(t, render, f, props)
				switch {
				case refusal == "":
					if got := dangerOf(out, t, images); got != baseline {
						t.Errorf("payload became live markup in %s: %d dangerous nodes, the golden has %d\n%s", where, got, baseline, out)
					}
					rendered++
				case strings.HasPrefix(refusal, "kit: "):
					refused++
				case strings.HasPrefix(refusal, "decode:"):
					// a typed value (a time, a number) that cannot hold text
					decodeRefused++
				default:
					t.Errorf("only validation may refuse (%s): %s", where, refusal)
				}
			}
		}
	}
	if rendered <= 500 {
		t.Errorf("payloads rendered = %d, want more than 500", rendered)
	}
	if refused <= 50 {
		t.Errorf("payloads refused by validation = %d, want more than 50", refused)
	}
	sort.Strings(components)
	t.Logf("%d components; %d payloads rendered, %d refused by validation, %d refused by decoding", len(components), rendered, refused, decodeRefused)
}

// attempt renders and reports a refusal: a panic message (starting "kit: "), a
// props decode error ("decode: ..."), a render error of a time that cannot
// parse ("decode: ..."), or "" for a render.
func attempt(t *testing.T, render renderer, f *fixture, props []byte) (out, refusal string) {
	t.Helper()
	defer func() {
		if r := recover(); r != nil {
			refusal = fmt.Sprint(r)
		}
	}()
	out, err := renderProps(t, render, f, props)
	if err != nil {
		var jerr *json.UnmarshalTypeError
		var perr *time.ParseError
		var serr *json.SyntaxError
		switch {
		case errors.As(err, &jerr), errors.As(err, &perr), errors.As(err, &serr), strings.HasPrefix(err.Error(), "props:"):
			return "", "decode: " + err.Error()
		}
		return "", "error: " + err.Error()
	}
	return out, ""
}

func TestTheSweepCatchesAnUnescapedProp(t *testing.T) {
	// A rendering that writes a prop raw must fail the check the sweep applies.
	bad := `<span>` + xssPayload + `</span>`
	if danger(bad, t) == 0 {
		t.Fatal("danger() does not see the payload in raw markup")
	}
	good := render(t, Badge(BadgeProps{Class: xssPayload}), plain(xssPayload))
	if danger(good, t) != 0 {
		t.Errorf("escaped payload counted as dangerous: %s", good)
	}
	if bytes.Contains([]byte(good), []byte("<script>")) {
		t.Error("script element in escaped output")
	}
}
