package main

import "github.com/flowcatalyst/framework/go/fw"

func newApp(opts ...fw.Option) *fw.App { return fw.New("locks-demo", opts...) }
