package main

import (
	"net/http"
	"strings"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// tokenResolver is the demo's ContextResolver until sessions and login exist (G5b): a caller that
// presents the configured token (orders.demo-token) is the demo's clerk. It is a fixed-token
// resolver of the kind the first application ran, not a model for authentication.
type tokenResolver struct {
	token  config.Secret
	client fw.ClientID
}

// Resolve implements httpfw.ContextResolver.
func (r tokenResolver) Resolve(req *http.Request) (fw.Context, error) {
	values := req.Header.Values("Authorization")
	if len(values) != 1 {
		return fw.Context{}, httpfw.Unauthenticated("a bearer token is required")
	}
	presented, ok := strings.CutPrefix(values[0], "Bearer ")
	if !ok || !r.token.Matches(presented) {
		return fw.Context{}, httpfw.Unauthenticated("a bearer token is required")
	}
	cx := fw.ContextFor(r.client).WithPrincipalOfKind(fw.MustPrincipalID("usr_demo"), fw.PrincipalUser)
	return cx.WithPermissions(orders.Read, orders.Manage), nil
}
