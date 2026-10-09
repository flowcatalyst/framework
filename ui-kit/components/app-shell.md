# App shell (hand-built navy sidebar + user menu)

**Source:** hand-built (`app/shell.rs`, `.fc-sidebar*` in `styles.css`)  
**Mechanism:** (b) Datastar signal on `<html>` + native `<details>` and `popover`  

## Purpose

FlowCatalyst's actual app chrome: a fixed navy sidebar (logo, collapse button, grouped nav with expandable children, account button) with a user-menu popover, and a main region.

## Structure

```html
<html lang="en" data-signals:sidebar-collapsed="{server: initial boolean from the fc_sidebar cookie}" data-class:fc-collapsed="$sidebarCollapsed">
...
<aside class="fc-sidebar">
  <div class="fc-sidebar-header">
    <a href="#" class="fc-sidebar-logo" style="height: 32px">
      <span style="width: 32px; height: 32px"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="100%" height="100%" aria-label="FlowCatalyst"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M13 10V3L4 14h7v7l9-11h-7z"/></svg></span>
    </a>
    <button type="button" class="fc-collapse-btn" aria-label="Collapse sidebar" aria-expanded="true"
            data-attr:aria-expanded="$sidebarCollapsed ? 'false' : 'true'"
            data-on:click="$sidebarCollapsed = !$sidebarCollapsed; document.cookie = 'fc_sidebar=' + ($sidebarCollapsed ? 'collapsed' : '') + ';path=/;max-age=31536000;samesite=lax'">
      <svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-left"/></svg>
    </button>
  </div>
  <nav class="fc-sidebar-nav" aria-label="Main">
    <div class="fc-nav-group">
      <span class="fc-nav-group-label">Platform</span>
      <a href="#" class="fc-nav-item" title="Dashboard" aria-current=page><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-layout-dashboard"/></svg><span class="fc-nav-label">Dashboard</span></a>
      <details open>
        <summary class="fc-nav-item" title="Authorization"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-shield-check"/></svg><span class="fc-nav-label">Authorization</span><svg width="1em" height="1em" class="fc-nav-chevron" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></summary>
        <div class="fc-nav-children">
          <a href="#" class="fc-nav-child"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-key-round"/></svg><span class="fc-nav-label">Roles</span></a>
          <a href="#" class="fc-nav-child"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-users"/></svg><span class="fc-nav-label">Users</span></a>
        </div>
      </details>
      <a href="#" class="fc-nav-item" title="Event types"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-workflow"/></svg><span class="fc-nav-label">Event types</span></a>
    </div>
    <div class="fc-nav-group">
      <span class="fc-nav-group-label">Settings</span>
      <a href="#" class="fc-nav-item" title="General"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-settings"/></svg><span class="fc-nav-label">General</span></a>
    </div>
  </nav>
  <div class="fc-sidebar-footer">
    <button type="button" class="fc-user-trigger" popovertarget="fc-user-menu" aria-label="Account menu">
      <span class="fc-avatar">AL</span>
      <span class="fc-user-text min-w-0 flex-1"><span class="fc-user-name">Ada Lovelace</span><span class="fc-user-email">ada@example.com</span></span>
      <svg width="1em" height="1em" class="shrink-0 text-white/50 fc-user-text size-[11px]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-up"/></svg>
    </button>
  </div>
</aside>
<div id="fc-user-menu" popover class="fc-user-menu">
  <div class="flex items-center gap-3 p-4">
    <span class="fc-avatar fc-avatar-lg">AL</span>
    <div class="min-w-0"><p class="truncate text-[15px] font-semibold">Ada Lovelace</p><p class="truncate text-[13px] text-muted-foreground">ada@example.com</p></div>
  </div>
  <div class="border-t border-border p-2"><a href="#" class="fc-menu-item"><svg width="1em" height="1em" class="size-[1.1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-user"/></svg> Profile</a><a href="#" class="fc-menu-item"><svg width="1em" height="1em" class="size-[1.1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-key-round"/></svg> Reset Password</a></div>
  <div class="border-t border-border p-2"><form method="post" action="#" data-on:submit="void 0"><button type="submit" class="fc-menu-item danger"><svg width="1em" height="1em" class="size-[1.1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-log-out"/></svg> Sign Out</button></form></div>
  <div class="flex justify-between border-t border-border px-4 py-2.5 text-xs text-muted-foreground"><span>Version</span><span>1.0.0</span></div>
</div>
<div class="fc-main">
  <main class="p-[24px]">
    <div role="status" class="fc-banner fc-banner-success mb-4">Saved.</div>
    <header class="fc-page-header"><div><h1 class="fc-page-title">Dashboard</h1><p class="fc-page-subtitle">App shell with the collapsible navy sidebar</p></div><div class="flex items-center gap-2"></div></header>
    <div class="fc-card">Collapse state is the <code>fc-collapsed</code> class on &lt;html&gt;, driven by the <code>sidebarCollapsed</code> signal.</div>
  </main>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Sidebar | `<aside class="fc-sidebar">` | `.fc-sidebar` (fixed, 260px, navy gradient) | 72px when `html.fc-collapsed` |
| Header | `.fc-sidebar-header` | - | Logo link `.fc-sidebar-logo` + `.fc-collapse-btn` |
| Collapse button | `<button class="fc-collapse-btn">` | - | `data-on:click="$sidebarCollapsed = !$sidebarCollapsed; document.cookie = ..."` |
| Nav | `<nav class="fc-sidebar-nav" aria-label="Main">` | - | Groups `.fc-nav-group` with `.fc-nav-group-label` |
| Item | `<a class="fc-nav-item" title>` | - | `aria-current="page"` = current; icon + `.fc-nav-label` |
| Expandable item | `<details open?><summary class="fc-nav-item">` + `.fc-nav-children` > `.fc-nav-child` | - | Native disclosure; chevron `.fc-nav-chevron` rotates when open |
| Footer / account | `.fc-sidebar-footer` > `<button class="fc-user-trigger" popovertarget="fc-user-menu">` | - | Opens the user menu |
| User menu | `<div id="fc-user-menu" popover class="fc-user-menu">` | - | Native popover: light dismiss and Escape are built in; items `.fc-menu-item` (`.danger` for Sign Out) |
| Main | `<div class="fc-main"><main class="p-[24px]">` | - | Shifts with `margin-left` 260px / 72px |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### collapsed (class on `<html>`)

| State | Effect |
|---|---|
| expanded | no class |
| collapsed | `fc-collapsed`: sidebar 72px, logo / group labels / labels / chevrons / children / user text hidden, items centred, `.fc-main` margin 72px |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/app-shell`, `fixtures/app-sidebar` and `fixtures/user-menu`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `logoHref` | string | - |  |
| `nav` | list<{label, items: list<{label, href, icon, current, open, children}>}> | [] | items with children are `<details>` groups |
| `user` | {name, email, initials} | - |  |
| `version` | string | - |  |
| `profileHref` | string | - |  |
| `resetPasswordHref` | string | - | empty hides the item (IdP-managed users) |
| `signOutAction` | string | - | POST target |
| `signOutAttrs` | attrs | - | on the Sign Out form |
| `default` | slot | - | page content inside `<main>` |

The `<html>` attributes (`data-signals:sidebar-collapsed` with the literal from the `fc_sidebar` cookie, `data-class:fc-collapsed`) belong to the page layout, not to this component; the fixtures cover the `<body>` content.

## Accessibility

- Collapse button: `aria-label="Collapse sidebar"` and `aria-expanded` (`data-attr:aria-expanded="$sidebarCollapsed ? 'false' : 'true'"`) (added in this port).
- Nav is a `<nav aria-label="Main">`; current item `aria-current="page"`; every item keeps its `title` for the icon-only state.
- The user menu is a native popover (Escape and outside click close it). Its trigger has `aria-label="Account menu"`.
- Expandable groups are `<details>` (keyboard operable).

## Behaviour

**Collapse (b):** signal `sidebarCollapsed` on `<html>` (`data-class:fc-collapsed="$sidebarCollapsed"`). The click also writes the `fc_sidebar=collapsed` cookie (path `/`) so the server can render `<html data-signals:sidebar-collapsed="true">` on the next load and avoid a flash. This replaces fc-web's inline `onclick` that toggled the class.

**Initial value:** `data-signals:sidebar-collapsed="document.cookie.includes('fc_sidebar=collapsed')"` works on a static page; with a server, render the literal `true`/`false` from the cookie instead (also covers `HttpOnly` or differently-scoped cookies).

**Menus (a):** `<details>` and `popover` are native.

## Notes for a template author

- Parameters: logo (URL / svg / default bolt), logo height, nav tree, current path, user name/email/initials, platform-admin flag, version string, IdP-managed flag (switches Reset Password between a link and a dialog).
- The `html` element carries Datastar attributes, so render them in the layout template. Datastar processes `<html>` (verified).
- The class names `fc-*` live in `theme/fc-components.css` and need no Tailwind scanning.
