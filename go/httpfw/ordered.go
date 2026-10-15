package httpfw

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
)

// entry is one member of an orderedMap.
type entry struct {
	key   string
	value any
}

// orderedMap is a JSON object whose members keep the order given (Go maps have none).
type orderedMap []entry

// MarshalJSONTo implements json.MarshalerTo.
func (m orderedMap) MarshalJSONTo(enc *jsontext.Encoder) error {
	if err := enc.WriteToken(jsontext.BeginObject); err != nil {
		return err
	}
	for _, e := range m {
		if err := enc.WriteToken(jsontext.String(e.key)); err != nil {
			return err
		}
		if err := json.MarshalEncode(enc, e.value); err != nil {
			return err
		}
	}
	return enc.WriteToken(jsontext.EndObject)
}
