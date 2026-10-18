package admin

import (
	"context"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// Build checks the whole registry and binds the skeleton's handlers: the navigation, the about
// operation and, when [Declared.Config] was called, the config section. It is called in the
// build function, after every package declared its sections (declaration is complete before any
// build). A broken registry is a *fw.StartupError naming every problem, before anything listens.
//
// The returned routes go to httpfw.NewServer with the application's other tables.
func (d *Declared) Build(b *fw.Boot) (*httpfw.Routes, error) {
	sections := d.Sections()
	var problems []fw.Problem
	seen := map[string]bool{}
	for _, text := range append(d.web.Problems(), Problems(sections, d.application)...) {
		if !seen[text] {
			seen[text] = true
			problems = append(problems, fw.Problem{Module: "admin", Message: text})
		}
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}
	startedAt := b.Clock().Now()
	environment := b.Environment()
	rt := httpfw.NewRoutes("admin")

	rt.Bind(httpfw.Handle(d.ops.navigation, func(_ context.Context, cx fw.Context, _ httpfw.In[httpfw.NoBody]) (Navigation, error) {
		views := []SectionView{}
		for _, s := range VisibleTo(sections, cx) {
			v := SectionView{ID: s.ID, Title: s.Title, Path: s.Path, Permission: s.Permission}
			if s.Icon != "" {
				v.Icon = &s.Icon
			}
			if s.Description != "" {
				v.Description = &s.Description
			}
			views = append(views, v)
		}
		return Navigation{Sections: views}, nil
	}))

	rt.Bind(httpfw.Handle(d.ops.about, func(ctx context.Context, _ fw.Context, _ httpfw.In[httpfw.NoBody]) (About, error) {
		report := b.HealthReport(ctx)
		return About{
			Application: d.application.String(), Environment: environment, StartedAt: startedAt.UTC().Truncate(time.Millisecond),
			Ready: report.IsReady(), Checks: Checks(report),
		}, nil
	}))

	d.bindConfig(b, rt)
	return rt, nil
}
