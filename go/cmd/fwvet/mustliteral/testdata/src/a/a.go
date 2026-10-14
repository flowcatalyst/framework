package a

import "github.com/flowcatalyst/framework/go/fw"

const goodClient = "acme"

var (
	_ = fw.MustClientID("acme")
	_ = fw.MustClientID(goodClient)
	_ = fw.MustClientID("a:b")                 // want `MustClientID: invalid id: ClientId must match`
	_ = fw.MustApplicationCode("Orders")       // want `MustApplicationCode: invalid id`
	_ = fw.MustPermission("orders:orders:write")
	_ = fw.MustPermission("orders::write")     // want `MustPermission: invalid id`
	_ = fw.MustIDPrefix("ord")
	_ = fw.MustIDPrefix("or_d")                // want `MustIDPrefix: TSID prefix must match`
)

func dynamic(s string) fw.ClientID {
	return fw.MustClientID(s) // want `MustClientID takes a string literal`
}
