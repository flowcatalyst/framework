// Package ratelimit is the framework's rate limiting (rate-limiting contract): GCRA limiters built
// in code, a memory limiter for one process and (package redisrl) a Redis limiter whose replicas
// share one budget, the HTTP middleware that answers 429 with the standard envelope, and the key
// resolvers that decide whose budget a request uses.
//
// Limiters are code, not configuration (config-and-code contract section 3.10): there is no
// rate-limits section and no registry. An application that wants its numbers tunable declares its
// own settings and passes the values in.
//
//	api, err := ratelimit.NewMemory("api", ratelimit.MustPolicy(100, time.Minute, 20))
//	limit, err := ratelimit.Middleware(api, ratelimit.ClientAddress())
//	routes.Handle("POST /login", handler, httpfw.With(limit))
//
// A request the limiter cannot decide because its store failed is let through by default (and
// logged and counted); a login or password-reset limiter should say [OnStoreFailure] [Deny].
package ratelimit
