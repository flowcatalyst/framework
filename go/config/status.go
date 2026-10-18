package config

import (
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// State says where the effective value of a setting comes from.
type State string

// The states.
const (
	// StateSet means a file or the environment gave a value.
	StateSet State = "SET"
	// StateDefault means no source gave one and the declared default applies.
	StateDefault State = "DEFAULT"
	// StateUnset means there is no value (an optional setting nothing sets).
	StateUnset State = "UNSET"
)

// SettingStatus is what a loaded configuration says about one declared setting, for tooling such as
// the admin config page: where the value came from and whether there is one, and the value itself
// only when that is safe to show (the Java SettingStatus). A secret's value is never here, and
// neither is the value of a setting whose key names a credential though it was declared as plain
// text; a URL is shown without user info and without query.
type SettingStatus struct {
	// Module is the module that declared the setting.
	Module string
	// Key is the dotted key.
	Key string
	// Type is the accepted shape in words, for example "an integer".
	Type string
	// Secret says the setting is declared as a secret.
	Secret bool
	// Required says start-up fails without a value.
	Required bool
	// State says whether a source gave a value, the default applies, or nothing does.
	State State
	// Source is "default", "not set", an environment variable or a file; never a value.
	Source string
	// Value is the value as text; empty when Withheld or the state is [StateUnset]. HasValue says
	// whether it is meaningful.
	Value string
	// HasValue says Value is the value to show.
	HasValue bool
	// Withheld says there is a value that is deliberately not shown.
	Withheld bool
	// Description is the setting's description, possibly empty.
	Description string
}

var credentialKey = regexp.MustCompile(`(?i)(secret|password|passwd|passphrase|token|credential|api[-_.]?key|private[-_.]?key|access[-_.]?key|signing[-_.]?key|salt)`)

// Statuses returns one status per declared setting, in declaration order. The statuses of a nil
// *Values are empty.
func (v *Values) Statuses() []SettingStatus {
	if v == nil {
		return []SettingStatus{}
	}
	out := make([]SettingStatus, len(v.statuses))
	copy(out, v.statuses)
	return out
}

func newStatus(module string, d Decl, value any, has bool, origin string) SettingStatus {
	st := SettingStatus{
		Module: module, Key: d.Key(), Type: d.describe(), Secret: d.isSecret(), Required: d.isRequired(),
		Source: origin, Description: d.text(),
	}
	switch {
	case !has:
		st.State, st.Source = StateUnset, "not set"
	case origin == "default":
		st.State = StateDefault
	default:
		st.State = StateSet
	}
	if !has {
		return st
	}
	if d.isSecret() || credentialKey.MatchString(d.Key()) {
		st.Withheld = true
		return st
	}
	st.Value, st.HasValue = render(value), true
	return st
}

func render(v any) string {
	switch x := v.(type) {
	case url.URL:
		return stripURL(x)
	case *url.URL:
		if x == nil {
			return ""
		}
		return stripURL(*x)
	case []string:
		return strings.Join(x, ",")
	case time.Duration:
		return durationText(x)
	case string:
		return x
	case int:
		return strconv.Itoa(x)
	case int64:
		return strconv.FormatInt(x, 10)
	case bool:
		return strconv.FormatBool(x)
	}
	return fmt.Sprint(v)
}

// stripURL is the URL without user info, query and fragment. An opaque URL (jdbc:postgresql://...)
// is cut at '?' and loses user info the same way.
func stripURL(u url.URL) string {
	text := u.String()
	if i := strings.IndexAny(text, "?#"); i >= 0 {
		text = text[:i]
	}
	if scheme := strings.Index(text, "://"); scheme >= 0 {
		rest := text[scheme+3:]
		authority, _, _ := strings.Cut(rest, "/")
		if at := strings.LastIndex(authority, "@"); at >= 0 {
			text = text[:scheme+3] + rest[at+1:]
		}
	}
	return text
}

// durationText is java.time.Duration.toString: PT30S, PT1H30M, PT0.5S, PT0S.
func durationText(d time.Duration) string {
	if d == 0 {
		return "PT0S"
	}
	neg := d < 0
	if neg {
		d = -d
	}
	hours := int64(d / time.Hour)
	minutes := int64(d%time.Hour) / int64(time.Minute)
	secs := int64(d%time.Minute) / int64(time.Second)
	nanos := int64(d % time.Second)
	sign := ""
	if neg {
		sign = "-"
	}
	var sb strings.Builder
	sb.WriteString("PT")
	if hours != 0 {
		sb.WriteString(sign + strconv.FormatInt(hours, 10) + "H")
	}
	if minutes != 0 {
		sb.WriteString(sign + strconv.FormatInt(minutes, 10) + "M")
	}
	if secs != 0 || nanos != 0 {
		sb.WriteString(sign + strconv.FormatInt(secs, 10))
		if nanos != 0 {
			frac := strings.TrimRight(fmt.Sprintf("%09d", nanos), "0")
			sb.WriteString("." + frac)
		}
		sb.WriteString("S")
	}
	return sb.String()
}
