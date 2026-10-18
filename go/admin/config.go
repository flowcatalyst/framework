package admin

import (
	"context"
	"strings"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// ConfigState is where the effective value of a setting comes from.
type ConfigState string

// The states.
const (
	// ConfigSet means a file or the environment gave a value.
	ConfigSet ConfigState = "SET"
	// ConfigDefault means no source gave one and the declared default applies.
	ConfigDefault ConfigState = "DEFAULT"
	// ConfigUnset means there is no value.
	ConfigUnset ConfigState = "UNSET"
)

// EnumValues implements openapi.EnumValuer.
func (ConfigState) EnumValues() []string { return []string{"SET", "DEFAULT", "UNSET"} }

// ConfigSetting is one declared setting (admin contract section 10). A secret's value is never
// on the wire.
type ConfigSetting struct {
	Key         string      `json:"key"`
	Module      string      `json:"module"`
	Type        string      `json:"type"`
	Secret      bool        `json:"secret"`
	Required    bool        `json:"required"`
	State       ConfigState `json:"state"`
	Source      string      `json:"source"`
	Value       *string     `json:"value,omitzero"`
	Withheld    bool        `json:"withheld"`
	Description *string     `json:"description,omitzero"`
}

// Health is the health of the application, as the about page reports it.
type Health struct {
	Ready  bool    `json:"ready"`
	Checks []Check `json:"checks"`
}

// ConfigReport is the settings that match, with the totals of all of them.
type ConfigReport struct {
	Environment string          `json:"environment"`
	Total       int32           `json:"total"`
	Set         int32           `json:"set"`
	Defaulted   int32           `json:"defaulted"`
	Unset       int32           `json:"unset"`
	Settings    []ConfigSetting `json:"settings"`
	Health      Health          `json:"health"`
}

type configOptions struct {
	values bool
	ops    configOperations
}

type configOperations struct {
	list httpfw.Operation[httpfw.NoBody, ConfigReport]
	get  httpfw.Operation[httpfw.NoBody, ConfigSetting]
}

var (
	configState  = httpfw.Query[ConfigState]("state").Optional()
	configSecret = httpfw.Query[bool]("secret").Optional()
	configQ      = httpfw.Query[string]("q").Optional()
	configKey    = httpfw.Path[string]("key")
)

// ConfigView is the permission that opens the config section: {app}:admin:config:view.
func ConfigView(application fw.ApplicationCode) string {
	return application.String() + ":admin:config:view"
}

// ConfigOption changes the config section.
type ConfigOption func(*configOptions)

// WithoutValues withholds every value: the section then shows state and source only.
func WithoutValues() ConfigOption { return func(o *configOptions) { o.values = false } }

// Config adds the config section (admin contract section 10): which settings the application
// declares, whether each has a value, where it came from, and the application's health. Secrets
// are reported as set or unset and never shown; neither is a value whose key names a credential,
// nor the user info and query of a URL. It needs {app}:admin:config:view. Call it before
// [Declared.Build].
//
// What it does not do: it does not re-run the modules' range checks (a value out of range fails
// start-up, so every setting shown has passed them), and it does not look at per-client secrets,
// which are looked up by client and name.
func (d *Declared) Config(opts ...ConfigOption) {
	o := &configOptions{values: true}
	for _, f := range opts {
		f(o)
	}
	httpfw.NameType[ConfigSetting](d.web, "AdminConfigSetting")
	httpfw.NameType[Health](d.web, "AdminHealth")
	httpfw.NameType[ConfigReport](d.web, "AdminConfig")
	view := permissionOf(ConfigView(d.application))
	o.ops = configOperations{
		list: httpfw.Get[ConfigReport]("/api/admin/config").
			ID("listAdminConfig").Summary("The application's settings, where they came from, and secret status").Tag("admin").
			Query(configState, configSecret, configQ).Permission(view).Build(),
		get: httpfw.Get[ConfigSetting]("/api/admin/config/{key}").
			ID("getAdminConfigSetting").Summary("One setting").Tag("admin").
			Path(configKey).Permission(view).
			Error(404, "SETTING_NOT_FOUND", "no such setting is declared").Build(),
	}
	d.mu.Lock()
	d.config = o
	d.mu.Unlock()
	d.Section(NewSection("config", "Configuration", "/admin/config", ConfigView(d.application)).
		WithIcon("i-lucide-sliders-horizontal").
		WithDescription("The settings the application runs with, where they came from, and secret status.").
		Serving(o.ops.list, o.ops.get))
}

func settingView(s config.SettingStatus, values bool) ConfigSetting {
	v := ConfigSetting{
		Key: s.Key, Module: s.Module, Type: s.Type, Secret: s.Secret, Required: s.Required,
		State: ConfigState(s.State), Source: s.Source, Withheld: s.Withheld,
	}
	if s.HasValue {
		if values {
			value := s.Value
			v.Value = &value
		} else {
			v.Withheld = true
		}
	}
	if s.Description != "" {
		description := s.Description
		v.Description = &description
	}
	return v
}

func (d *Declared) bindConfig(b *fw.Boot, rt *httpfw.Routes) {
	d.mu.Lock()
	o := d.config
	d.mu.Unlock()
	if o == nil {
		return
	}
	rt.Bind(httpfw.Handle(o.ops.list, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (ConfigReport, error) {
		all := b.SettingStatuses()
		state, hasState := configState.Value(in.Params)
		secret, hasSecret := configSecret.Value(in.Params)
		q, hasQ := configQ.Value(in.Params)
		needle := strings.ToLower(q)
		out := ConfigReport{Environment: b.Environment(), Settings: []ConfigSetting{}}
		for _, s := range all {
			switch s.State {
			case config.StateSet:
				out.Set++
			case config.StateDefault:
				out.Defaulted++
			case config.StateUnset:
				out.Unset++
			}
			if hasState && string(state) != string(s.State) {
				continue
			}
			if hasSecret && secret != s.Secret {
				continue
			}
			if hasQ && !strings.Contains(strings.ToLower(s.Key), needle) && !strings.Contains(strings.ToLower(s.Module), needle) {
				continue
			}
			out.Settings = append(out.Settings, settingView(s, o.values))
		}
		out.Total = int32(len(out.Settings)) //nolint:gosec // a handful of settings
		report := b.HealthReport(ctx)
		out.Health = Health{Ready: report.IsReady(), Checks: Checks(report)}
		return out, nil
	}))
	rt.Bind(httpfw.Handle(o.ops.get, func(_ context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (ConfigSetting, error) {
		key := configKey.Value(in.Params)
		for _, s := range b.SettingStatuses() {
			if s.Key == key {
				return settingView(s, o.values), nil
			}
		}
		return ConfigSetting{}, httpfw.NewError(404, "SETTING_NOT_FOUND", "no setting '"+key+"' is declared")
	}))
}
