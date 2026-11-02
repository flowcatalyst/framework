package main

import "testing"

func TestParseServeNeedsAnExplicitPortAndRefusesTheDevelopmentPorts(t *testing.T) {
	for _, tc := range []struct {
		args []string
		port int
		ok   bool
	}{
		{[]string{"serve", "--port", "4711"}, 4711, true},
		{[]string{"serve", "--port=4711"}, 4711, true},
		{[]string{"serve"}, 0, false},
		{nil, 0, false},
		{[]string{"serve", "--port", "x"}, 0, false},
		{[]string{"serve", "--port", "0"}, 0, false},
		{[]string{"serve", "--port", "8080"}, 0, false},
		{[]string{"serve", "--port", "15432"}, 0, false},
		{[]string{"run", "--port", "4711"}, 0, false},
	} {
		port, err := parseServe(tc.args)
		if (err == nil) != tc.ok || port != tc.port {
			t.Errorf("parseServe(%v) = %d, %v; want %d, ok=%v", tc.args, port, err, tc.port, tc.ok)
		}
	}
}
