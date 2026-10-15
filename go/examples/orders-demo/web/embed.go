// Package web holds the orders-demo's single-page application as the binary embeds it
// (static-assets contract section 8). go:embed cannot reach outside its module, so the build output
// of the SPA (ui/apps/orders) is copied into dist/ before the Go build; the committed dist/ is a
// placeholder page that says so.
package web

import (
	"embed"
	"io/fs"
)

//go:embed all:dist
var embedded embed.FS

// Dist returns the SPA's files, rooted at the directory that holds index.html.
func Dist() fs.FS {
	sub, err := fs.Sub(embedded, "dist")
	if err != nil {
		panic(err) // dist is embedded above
	}
	return sub
}

//go:embed openapi.json
var openAPI []byte

// OpenAPI returns the committed OpenAPI document (web/openapi.json), which `orders-demo openapi
// --out web/openapi.json` writes and the SPA's client is generated from.
func OpenAPI() []byte { return openAPI }
