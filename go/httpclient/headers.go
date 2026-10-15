package httpclient

import (
	"fmt"
	"regexp"
	"strings"
)

var headerToken = regexp.MustCompile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

var reservedHeaders = map[string]bool{"host": true, "content-length": true, "transfer-encoding": true, "connection": true}

func checkHeaderName(name string) error {
	if !headerToken.MatchString(name) {
		return fmt.Errorf("%w: not a header name: %s", ErrInvalidRequest, printable(name))
	}
	if reservedHeaders[strings.ToLower(name)] {
		return fmt.Errorf("%w: header %s is set by the client", ErrInvalidRequest, name)
	}
	return nil
}

func checkHeaderValue(name, value string) error {
	if strings.ContainsAny(value, "\r\n\x00") {
		return fmt.Errorf("%w: the value of header %s contains CR, LF or NUL", ErrInvalidRequest, name)
	}
	return nil
}

func checkHeader(name, value string) error {
	if err := checkHeaderName(name); err != nil {
		return err
	}
	return checkHeaderValue(name, value)
}

// printable replaces everything outside printable ASCII, so a bad name cannot break a log line.
func printable(s string) string {
	var sb strings.Builder
	for _, r := range s {
		if r < ' ' || r > '~' {
			sb.WriteByte('?')
		} else {
			sb.WriteRune(r)
		}
	}
	return sb.String()
}
