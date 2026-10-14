package main

import (
	"context"
	"os"
)

func main() {
	app := newApp()
	d := declare(app)
	os.Exit(app.Launch(context.Background(), os.Args[1:], build(d, deps{out: os.Stdout, trace: func(string) {}})))
}
