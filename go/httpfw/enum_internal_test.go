package httpfw

import (
	"encoding/json/v2"
	"strings"
	"testing"
)

type enumShade string

func (enumShade) EnumValues() []string { return []string{"LIGHT", "DARK"} }

type enumBody struct {
	Shade enumShade   `json:"shade"`
	Maybe *enumShade  `json:"maybe,omitzero"`
	List  []enumShade `json:"list,omitzero"`
}

// A body field of an enum type accepts only the declared values, with no validate rule written
// (friction G76-6): the failure is the framework's INVALID_JSON detail at the member's path.
func TestBodyEnumFieldsAcceptOnlyDeclaredValues(t *testing.T) {
	t.Parallel()
	var ok enumBody
	if err := json.Unmarshal([]byte(`{"shade":"DARK","maybe":"LIGHT","list":["DARK","LIGHT"]}`), &ok, jsonOptions); err != nil {
		t.Fatal(err)
	}
	if ok.Shade != "DARK" || ok.Maybe == nil || *ok.Maybe != "LIGHT" || len(ok.List) != 2 {
		t.Fatalf("a valid body was decoded as %+v", ok)
	}
	for body, field := range map[string]string{
		`{"shade":"SOON"}`:             "shade",
		`{"shade":""}`:                 "shade",
		`{"shade":"dark"}`:             "shade",
		`{"shade":1}`:                  "shade",
		`{"shade":"DARK","maybe":"X"}`: "maybe",
		`{"list":["DARK","X"]}`:        "list[1]",
	} {
		var b enumBody
		err := json.Unmarshal([]byte(body), &b, jsonOptions)
		if err == nil {
			t.Errorf("%s: accepted", body)
			continue
		}
		e := invalidJSON(err)
		d := e.Details()
		if e.Status() != 400 || len(d) != 1 || !strings.HasPrefix(d[field], "INVALID_JSON: wrong type or format") {
			t.Errorf("%s: %d %v", body, e.Status(), d)
		}
	}
}
