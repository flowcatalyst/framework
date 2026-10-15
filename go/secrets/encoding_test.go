package secrets

import (
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
)

func TestTheEncodingNeverMapsTwoReferencesToOneVariable(t *testing.T) {
	s := &envStore{instance: "e", prefix: "CLIENT_SECRET_"}
	parts := []string{"a", "b", "a-b", "b-a", "ab", "a-b-c", "c", "b-c", "a1", "1a"}
	seen := map[string]string{}
	for _, client := range parts {
		for _, name := range parts {
			v, ok := s.variable(fw.MustClientID(client), name)
			if !ok {
				t.Fatalf("client %q not encodable", client)
			}
			if prev, dup := seen[v]; dup {
				t.Fatalf("%s/%s and %s both map to %s", client, name, prev, v)
			}
			seen[v] = client + "/" + name
		}
	}
	if v, _ := s.variable(fw.MustClientID("acme-corp"), "sftp-password"); v != "CLIENT_SECRET_ACME_CORP__SFTP_PASSWORD" {
		t.Fatalf("got %s", v)
	}
}
