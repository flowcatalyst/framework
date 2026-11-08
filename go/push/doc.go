// Package push sends small messages to browsers, apps and field devices listening over
// server-sent events (push contract, docs/contracts/push.md). A push is written in the same
// transaction as the change it announces (usecase.Push in a use case, [Pushes.Publish] outside
// one), so it is sent only if that transaction commits; one relay at a time moves the committed
// pushes, in order, into a log every instance follows (the A1 driver, [PostgresLog], keeps it in
// fw_push_log); each instance's hub fans them out to its streams by topic and client, replays what
// a reconnecting client missed and sends a reset to one that was away too long.
//
//	pd := push.Declare(app)                                       // in main: settings, migration, rules
//	pd.Topic("fulfil:order:{id}", push.Requires("fulfil:order:view"))
//	pd.Topic("fulfil:field-worker:{principal}:jobs")
//	pushes, err := pd.Build(ctx, b, db, lk, push.Authenticate(push.AuthModule(authModule)))
//	runner, err := usecase.NewRunner(b, outbox, db, pushes.RunnerOption())
//	srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{authModule.Routes(), pushes.Routes()}, ...)
package push
