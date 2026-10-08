# Tabs

**Source:** upstream Topcoat UI registry 0.9.0 (`tabs.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (b) Datastar signal (client tabs) or (a) plain links / (c) server-rendered panel  

## Purpose

A row of triggers that selects which panel is visible.

## Structure

```html
<div class="flex flex-col gap-4" data-signals:tabs-demo="'overview'">
    <div class="inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1">
      <a href="#tabsDemo-overview" class="inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background text-muted-foreground hover:bg-foreground/5 hover:text-foreground aria-[current=page]:bg-foreground/10 aria-[current=page]:text-foreground aria-[current=page]:hover:bg-foreground/10" data-attr:aria-current="$tabsDemo === 'overview' && 'page'" data-on:click__prevent="$tabsDemo = 'overview'">Overview</a>
      <a href="#tabsDemo-members" class="inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background text-muted-foreground hover:bg-foreground/5 hover:text-foreground aria-[current=page]:bg-foreground/10 aria-[current=page]:text-foreground aria-[current=page]:hover:bg-foreground/10" data-attr:aria-current="$tabsDemo === 'members' && 'page'" data-on:click__prevent="$tabsDemo = 'members'">Members</a>
    </div>
    <div id="tabsDemo-overview" data-show="$tabsDemo === 'overview'">Overview panel</div>
    <div id="tabsDemo-members" data-show="$tabsDemo === 'members'" style="display: none">Members panel</div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| tabs | `<div>` | `flex flex-col gap-4` + `data-signals:{kebab-name}="'{first}'"` | Declares the selected-tab signal |
| tabs_list | `<div>` | `inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1` | Trigger row |
| tabs_trigger | `<a href="#{sig}-{value}">` | `inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background text-muted-foreground hover:bg-foreground/5 hover:text-foreground aria-[current=page]:bg-foreground/10 aria-[current=page]:text-foreground aria-[current=page]:hover:bg-foreground/10` | Selected look comes from `aria-current="page"` |
| tabs_content | `<div id="{sig}-{value}">` | (none) | Panel; `data-show` toggles `display` |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### trigger state

| State | How |
|---|---|
| selected | `aria-current="page"`: `data-attr:aria-current="$sig === 'v' && 'page'"` (false removes the attribute) |
| unselected | no `aria-current` |

### Trigger attributes

| Purpose | Attribute |
|---|---|
| select | `data-on:click__prevent="$sig = 'v'"` |
| no-JS fallback | `href="#{sig}-{value}"` jumps to the panel (all panels are visible without Datastar) |

### Panel attributes

| Purpose | Attribute |
|---|---|
| show/hide | `data-show="$sig === 'v'"` |
| avoid flash before Datastar loads | `style="display: none"` on every panel except the first |

## Accessibility

- Triggers are links with `aria-current` (as upstream), not the ARIA tab pattern: no arrow-key roving. If the ARIA pattern is required, add `role="tablist"`, `role="tab"` + `aria-selected`, `role="tabpanel"` + `aria-labelledby`, and a roving `tabindex` with `data-on:keydown`; that is not done here to stay faithful.
- Panels have ids, so the link fallback and deep links work.

## Behaviour

**Client tabs (b):** one signal, `$sig`, holds the selected value; triggers set it; `data-attr:aria-current` and `data-show` read it. Verified in the gallery (aria-current moves, panel visibility switches, URL hash unchanged).

**Server tabs (a):** real `href="?tab=members"` links, the server renders one panel and sets `aria-current="page"` on the active trigger. No Datastar.

**Lazy panel (c):** `data-on:click__prevent="$sig = 'members'; @get('/team/members')"`; the server patches `#{sig}-members` (mode inner).

## Notes for a template author

- **Signal names are global to the page**: every `tabs` instance needs its own (`tabsTeam`, `tabsBilling`). In HTML the attribute key is kebab-case (`data-signals:tabs-team`), in expressions it is camelCase (`$tabsTeam`).
- Parameters: signal name, tab `(value, label)` list, initial value. Slots: one panel per value.
