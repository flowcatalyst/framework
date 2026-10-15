// Package httpclient is the framework's HTTP client (http-client contract): a client per consumed
// service with its base URL, timeouts, retry policy and authentication strategy, on net/http. It
// imports nothing else of the framework, so it is usable alone, as framework-httpclient is; the
// adapter that names clients in code, reads their URLs and credentials from configuration, records
// metrics and propagates the correlation id is package httpclient/module.
//
//	engine := httpclient.NewEngine()
//	defer engine.Close()
//	orders, err := engine.Service("orders", baseURL, httpclient.Retry(httpclient.DefaultRetry()))
//	order, err := httpclient.ReceiveJSON[Order](ctx, orders.Get("/orders/{id}").PathParam("id", "42"))
//
// Send returns an error only when there is no response; a status is data. Redirects are not
// followed (following one could send the Authorization header to another host). A request can
// never leave its service's host: the path is checked, placeholder values are percent-encoded as
// one segment, and the URL is never taken from input. Error messages name the service, the method
// and the path template, never a header value, a body, a token or a query string.
package httpclient
