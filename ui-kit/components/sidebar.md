# Sidebar (Topcoat sidebar)

**Source:** upstream Topcoat UI registry 0.9.0 (`sidebar.rs`), not installed in fc-web; theme tokens applied; `--sidebar-*` tokens set to the navy chrome (new)  
**Mechanism:** (b) Datastar signals (collapse, mobile sheet); optional (c) to persist the state  

## Purpose

The app shell's side navigation: a desktop panel that collapses to an icon strip (or off-canvas) and becomes a sheet below 48rem (`md`), beside a scrolling inset content region. The complete composition is below; the parts table lists every class.

## Structure

```html
<div data-sidebar="provider" data-signals="{sidebarOpen: true, sidebarMobileOpen: false}" class="flex min-h-svh w-full md:h-svh md:overflow-hidden [--sidebar-width:16rem] [--sidebar-width-mobile:18rem] [--sidebar-width-icon:3rem] md:has-[[data-variant=inset]]:bg-sidebar">
<aside data-sidebar="sidebar" data-side="left" data-variant="sidebar" data-state="expanded"
       data-attr:data-state="$sidebarOpen ? 'expanded' : 'collapsed'"
       data-attr:data-collapsible="$sidebarOpen ? '' : 'icon'"
       class="group/sidebar relative w-0 shrink-0 text-sidebar-foreground md:sticky md:top-0 md:h-svh md:w-(--sidebar-width) md:self-start md:transition-[width] md:duration-200 md:data-[collapsible=offcanvas]:w-0 motion-reduce:transition-none md:data-[collapsible=icon]:w-(--sidebar-width-icon)">
  <dialog role="navigation" aria-label="Sidebar" data-attr:open="$sidebarMobileOpen"
          data-on:click="evt.target === el && ($sidebarMobileOpen = false)"
          data-on:keydown="evt.key === 'Escape' && ($sidebarMobileOpen = false)"
          class="fixed inset-0 z-50 size-full max-h-none max-w-none overflow-hidden bg-background/80 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete] md:relative md:inset-auto md:flex md:overflow-visible md:bg-transparent md:opacity-100 md:backdrop-blur-none md:starting:open:opacity-100 md:transition-none md:group-data-[collapsible=offcanvas]/sidebar:invisible motion-reduce:transition-none">
    <div data-sidebar="panel" class="flex flex-col gap-4 overflow-y-auto border-border bg-card p-6 text-card-foreground shadow-sm [transition:translate_200ms_ease-out] mr-auto h-full w-full max-w-sm border-r -translate-x-full in-[[open]]:translate-x-0 starting:in-[[open]]:-translate-x-full [--background:var(--sidebar)] [--foreground:var(--sidebar-foreground)] [--card:var(--sidebar)] [--card-foreground:var(--sidebar-foreground)] [--primary:var(--sidebar-primary)] [--primary-foreground:var(--sidebar-primary-foreground)] [--border:var(--sidebar-border)] [--ring:var(--sidebar-ring)] [--muted-foreground:color-mix(in_oklab,var(--sidebar-foreground)_70%,transparent)] relative [&]:w-(--sidebar-width-mobile) [&]:max-w-[calc(100vw-3rem)] [&]:gap-0 [&]:overflow-hidden [&]:p-0 md:[&]:w-full md:[&]:max-w-none md:[&]:translate-x-0 md:[&]:transition-none motion-reduce:transition-none md:shadow-none">
    <div data-sidebar="header" class="flex h-14 shrink-0 flex-col justify-center gap-2 border-b border-border px-2">
      <div class="flex items-center justify-between gap-2 px-2">
        <span class="font-semibold">FlowCatalyst</span>
        <button type="button" aria-label="Close sidebar" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg md:hidden" data-on:click="$sidebarMobileOpen = false"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></button>
      </div>
    </div>
    <div data-sidebar="content" class="flex min-h-0 flex-1 flex-col gap-2 overflow-x-hidden overflow-y-auto overscroll-contain">
      <div data-sidebar="group" class="relative flex min-w-0 flex-col gap-1 p-2">
        <div data-sidebar="group-label" class="flex h-8 shrink-0 items-center rounded-md px-2 text-xs font-medium text-sidebar-foreground/70 md:group-data-[collapsible=icon]/sidebar:hidden">Platform</div>
        <div data-sidebar="group-content" class="w-full text-sm">
          <ul data-sidebar="menu" class="flex min-w-0 flex-col gap-1">
          <li data-sidebar="menu-item" class="group/menu-item relative"><a href="#" data-sidebar="menu-button" data-active="true" aria-current="page" title="Dashboard" class="flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only border-transparent h-8 text-sm"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-layout-dashboard"/></svg><span>Dashboard</span></a></li>
          <li data-sidebar="menu-item" class="group/menu-item relative"><a href="#" data-sidebar="menu-button" data-active="false" title="Users" class="flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only border-transparent h-8 text-sm"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-users"/></svg><span>Users</span></a><span data-sidebar="menu-badge" class="pointer-events-none absolute top-1 right-1 flex h-6 min-w-6 items-center justify-center rounded-md px-1 text-xs tabular-nums text-sidebar-foreground/70 md:group-data-[collapsible=icon]/sidebar:hidden">12</span></li>
          <li data-sidebar="menu-item" class="group/menu-item relative"><a href="#" data-sidebar="menu-button" data-active="false" title="Roles" class="flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only border-transparent h-8 text-sm"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-shield-check"/></svg><span>Roles</span></a></li>
          <li data-sidebar="menu-item" class="group/menu-item relative"><a href="#" data-sidebar="menu-button" data-active="false" title="Event types" class="flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only border-transparent h-8 text-sm"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-workflow"/></svg><span>Event types</span></a>
            <ul data-sidebar="menu-sub" class="mx-3.5 flex min-w-0 flex-col gap-1 border-l border-sidebar-border px-2.5 py-1 md:group-data-[collapsible=icon]/sidebar:hidden">
              <li data-sidebar="menu-sub-item" class="relative"><a href="#" data-sidebar="menu-sub-button" aria-current=page class="flex min-w-0 items-center gap-2 rounded-md px-2 text-sidebar-foreground/70 outline-none hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring aria-[current=page]:bg-sidebar-accent aria-[current=page]:text-sidebar-accent-foreground aria-disabled:pointer-events-none aria-disabled:opacity-50 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate h-8 text-sm"><span>Definitions</span></a></li>
              <li data-sidebar="menu-sub-item" class="relative"><a href="#" data-sidebar="menu-sub-button"  class="flex min-w-0 items-center gap-2 rounded-md px-2 text-sidebar-foreground/70 outline-none hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring aria-[current=page]:bg-sidebar-accent aria-[current=page]:text-sidebar-accent-foreground aria-disabled:pointer-events-none aria-disabled:opacity-50 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate h-8 text-sm"><span>Schemas</span></a></li>
            </ul></li>
          </ul>
        </div>
      </div>
      <div data-sidebar="group" class="relative flex min-w-0 flex-col gap-1 p-2">
        <div data-sidebar="group-label" class="flex h-8 shrink-0 items-center rounded-md px-2 text-xs font-medium text-sidebar-foreground/70 md:group-data-[collapsible=icon]/sidebar:hidden">Settings</div>
        <div data-sidebar="group-content" class="w-full text-sm">
          <ul data-sidebar="menu" class="flex min-w-0 flex-col gap-1">
          <li data-sidebar="menu-item" class="group/menu-item relative"><a href="#" data-sidebar="menu-button" data-active="false" title="General" class="flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only border-transparent h-8 text-sm"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-settings"/></svg><span>General</span></a></li>
          <li data-sidebar="menu-item" class="group/menu-item relative"><a href="#" data-sidebar="menu-button" data-active="false" title="API access" class="flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only border-transparent h-8 text-sm"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-key-round"/></svg><span>API access</span></a></li>
          </ul>
        </div>
      </div>
    </div>
    <div data-sidebar="footer" class="flex shrink-0 flex-col gap-2 p-2">
      <ul data-sidebar="menu" class="flex min-w-0 flex-col gap-1"><li data-sidebar="menu-item" class="group/menu-item relative"><a href="#" data-sidebar="menu-button" data-active="false" title="Ada Lovelace" class="flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only border-transparent h-8 text-sm"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-user"/></svg><span>Ada Lovelace</span></a></li></ul>
    </div>
    <button type="button" data-sidebar="rail" aria-label="Toggle sidebar" title="Toggle sidebar" aria-expanded="true"
            data-attr:aria-expanded="$sidebarOpen ? 'true' : 'false'" data-on:click="$sidebarOpen = !$sidebarOpen" class="absolute inset-y-0 z-10 hidden w-2 cursor-pointer outline-none after:absolute after:inset-y-0 after:w-px hover:after:bg-sidebar-border focus-visible:after:bg-sidebar-ring md:block group-data-[side=left]/sidebar:right-0 group-data-[side=left]/sidebar:after:right-0 group-data-[side=right]/sidebar:left-0 group-data-[side=right]/sidebar:after:left-0"></button>
    </div>
  </dialog>
</aside>
<main data-sidebar="inset" class="relative flex min-w-0 flex-1 flex-col bg-background md:min-h-0 md:overflow-y-auto [&>[data-sidebar=header]]:sticky [&>[data-sidebar=header]]:top-0 [&>[data-sidebar=header]]:z-20 [&>[data-sidebar=header]]:flex-row [&>[data-sidebar=header]]:items-center [&>[data-sidebar=header]]:justify-start [&>[data-sidebar=header]]:gap-3 [&>[data-sidebar=header]]:bg-background [&>[data-sidebar=header]]:px-4 [&>[data-sidebar=header]>[aria-orientation=vertical]]:h-4 md:in-[[data-sidebar=provider]:has([data-variant=floating])]:my-[calc(--spacing(2)+1px)] md:in-[[data-sidebar=provider]:has([data-variant=inset])]:m-2 md:in-[[data-sidebar=provider]:has([data-variant=inset][data-side=left])]:ml-0 md:in-[[data-sidebar=provider]:has([data-variant=inset][data-side=right])]:mr-0 md:in-[[data-sidebar=provider]:has([data-variant=inset])]:rounded-xl md:in-[[data-sidebar=provider]:has([data-variant=inset])]:shadow-sm md:in-[[data-sidebar=provider]:has([data-variant=inset])]:ring-1 md:in-[[data-sidebar=provider]:has([data-variant=inset])]:ring-sidebar-border">
  <div data-sidebar="header" class="flex h-14 shrink-0 flex-col justify-center gap-2 border-b border-border px-2">
    <span class="hidden md:contents"><button type="button" data-sidebar="trigger" aria-label="Toggle sidebar" aria-expanded="true" data-attr:aria-expanded="$sidebarOpen ? 'true' : 'false'" data-on:click="$sidebarOpen = !$sidebarOpen" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-panel-left"/></svg></button></span><span class="md:hidden"><button type="button" data-sidebar="trigger" aria-label="Open sidebar" data-on:click="$sidebarMobileOpen = true" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-panel-left"/></svg></button></span>
    <hr aria-orientation="vertical" class="shrink-0 border-0 bg-border h-full w-px">
    <nav aria-label="breadcrumb">
  <ol class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
    <li class="inline-flex items-center gap-2"><a href="#" class="transition-colors hover:text-foreground">Platform</a></li>
    <li aria-hidden="true"><svg width="1em" height="1em" class="size-3.5" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></li>
    <li class="inline-flex items-center gap-2"><span aria-current="page" class="font-medium text-foreground">Dashboard</span></li>
  </ol>
</nav>
  </div>
  <div class="flex flex-col gap-4 p-4">
    <div class="flex flex-col gap-5 rounded-xl border border-border bg-card py-6 text-card-foreground shadow-sm"><div class="flex flex-col gap-1.5 px-6"><h3 class="text-base leading-none font-semibold">Overview</h3><p class="text-sm text-muted-foreground">Use the panel button, the rail, or the mobile trigger.</p></div></div>
  </div>
</main>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| provider | `<div data-sidebar="provider" data-signals="{sidebarOpen: true, sidebarMobileOpen: false}">` | `flex min-h-svh w-full md:h-svh md:overflow-hidden [--sidebar-width:16rem] [--sidebar-width-mobile:18rem] [--sidebar-width-icon:3rem] md:has-[[data-variant=inset]]:bg-sidebar` | Flex layout; the two signals live here. Width variables `--sidebar-width` (16rem), `--sidebar-width-mobile` (18rem), `--sidebar-width-icon` (3rem) can be overridden through `style` |
| sidebar | `<aside data-sidebar="sidebar" data-side data-variant data-state data-collapsible>` | `group/sidebar relative w-0 shrink-0 text-sidebar-foreground md:sticky md:top-0 md:h-svh md:w-(--sidebar-width) md:self-start md:transition-[width] md:duration-200 md:data-[collapsible=offcanvas]:w-0 motion-reduce:transition-none` + variant/collapsible classes | State attributes are driven by `data-attr:data-state` / `data-attr:data-collapsible` |
| sheet (inside) | `<dialog role="navigation" aria-label="Sidebar" data-attr:open>` | `fixed inset-0 z-50 size-full max-h-none max-w-none overflow-hidden bg-background/80 text-foreground backdrop-blur-sm open:flex` `opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]` + `md:relative md:inset-auto md:flex md:overflow-visible md:bg-transparent md:opacity-100 md:backdrop-blur-none md:starting:open:opacity-100 md:transition-none md:group-data-[collapsible=offcanvas]/sidebar:invisible motion-reduce:transition-none` | On desktop (`md:`) the same `<dialog>` is rendered as an inline panel; below `md` it is the sheet |
| panel | `<div data-sidebar="panel">` | `flex flex-col gap-4 overflow-y-auto border-border bg-card p-6 text-card-foreground shadow-sm [transition:translate_200ms_ease-out]` + side/motion + `[--background:var(--sidebar)] [--foreground:var(--sidebar-foreground)] [--card:var(--sidebar)] [--card-foreground:var(--sidebar-foreground)] [--primary:var(--sidebar-primary)] [--primary-foreground:var(--sidebar-primary-foreground)] [--border:var(--sidebar-border)] [--ring:var(--sidebar-ring)] [--muted-foreground:color-mix(in_oklab,var(--sidebar-foreground)_70%,transparent)]` + `relative [&]:w-(--sidebar-width-mobile) [&]:max-w-[calc(100vw-3rem)] [&]:gap-0 [&]:overflow-hidden [&]:p-0 md:[&]:w-full md:[&]:max-w-none md:[&]:translate-x-0 md:[&]:transition-none motion-reduce:transition-none` + variant `md:shadow-none` | `PANEL_THEME` re-points `--background/--foreground/--primary/--border/...` at the `--sidebar-*` tokens so nested controls inherit the palette |
| rail | `<button data-sidebar="rail" aria-label="Toggle sidebar">` | `absolute inset-y-0 z-10 hidden w-2 cursor-pointer outline-none after:absolute after:inset-y-0 after:w-px hover:after:bg-sidebar-border focus-visible:after:bg-sidebar-ring md:block group-data-[side=left]/sidebar:right-0 group-data-[side=left]/sidebar:after:right-0 group-data-[side=right]/sidebar:left-0 group-data-[side=right]/sidebar:after:left-0` | Desktop edge button. **Must be inside the panel** (the sheet's `z-50` otherwise covers it) |
| trigger | `<button data-sidebar="trigger" aria-label="Toggle sidebar">` | button `ghost`/`icon` + panel-left icon | Wrap the desktop trigger in `<span class="hidden md:contents">` and the mobile one in `<span class="md:hidden">` |
| inset | `<main data-sidebar="inset">` | `relative flex min-w-0 flex-1 flex-col bg-background md:min-h-0 md:overflow-y-auto [&>[data-sidebar=header]]:sticky [&>[data-sidebar=header]]:top-0 [&>[data-sidebar=header]]:z-20 [&>[data-sidebar=header]]:flex-row [&>[data-sidebar=header]]:items-center [&>[data-sidebar=header]]:justify-start [&>[data-sidebar=header]]:gap-3 [&>[data-sidebar=header]]:bg-background [&>[data-sidebar=header]]:px-4 [&>[data-sidebar=header]>[aria-orientation=vertical]]:h-4 md:in-[[data-sidebar=provider]:has([data-variant=floating])]:my-[calc(--spacing(2)+1px)] md:in-[[data-sidebar=provider]:has([data-variant=inset])]:m-2 md:in-[[data-sidebar=provider]:has([data-variant=inset][data-side=left])]:ml-0 md:in-[[data-sidebar=provider]:has([data-variant=inset][data-side=right])]:mr-0 md:in-[[data-sidebar=provider]:has([data-variant=inset])]:rounded-xl md:in-[[data-sidebar=provider]:has([data-variant=inset])]:shadow-sm md:in-[[data-sidebar=provider]:has([data-variant=inset])]:ring-1 md:in-[[data-sidebar=provider]:has([data-variant=inset])]:ring-sidebar-border` | Content; an immediate `data-sidebar="header"` child becomes a sticky toolbar |
| header | `<div data-sidebar="header">` | `flex h-14 shrink-0 flex-col justify-center gap-2 border-b border-border px-2` | Sidebar or inset header |
| content | `<div data-sidebar="content">` | `flex min-h-0 flex-1 flex-col gap-2 overflow-x-hidden overflow-y-auto overscroll-contain` | Scrolling region |
| footer | `<div data-sidebar="footer">` | `flex shrink-0 flex-col gap-2 p-2` |  |
| group | `<div data-sidebar="group">` | `relative flex min-w-0 flex-col gap-1 p-2` |  |
| group_label | `<div data-sidebar="group-label">` | `flex h-8 shrink-0 items-center rounded-md px-2 text-xs font-medium text-sidebar-foreground/70 md:group-data-[collapsible=icon]/sidebar:hidden` | Hidden when collapsed to icons |
| group_content | `<div data-sidebar="group-content">` | `w-full text-sm` |  |
| group_action | `<button data-sidebar="group-action">` | `absolute flex size-6 items-center justify-center rounded-md text-sidebar-foreground/70 outline-none hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 md:group-data-[collapsible=icon]/sidebar:hidden [&_svg]:size-4` `top-3 right-3` | Needs `aria-label` |
| menu | `<ul data-sidebar="menu">` | `flex min-w-0 flex-col gap-1` |  |
| menu_item | `<li data-sidebar="menu-item">` | `group/menu-item relative` |  |
| menu_button | `<a>` (with `href`) or `<button type=button>` | `flex w-full min-w-0 items-center gap-2 overflow-hidden rounded-md border px-2 text-left outline-none transition-colors hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground active:bg-sidebar-accent active:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 data-[active=true]:bg-sidebar-accent data-[active=true]:text-sidebar-accent-foreground data-[active=true]:font-medium has-[+[data-sidebar=menu-action]]:pr-8 has-[+[data-sidebar=menu-badge]]:pr-8 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate md:group-data-[collapsible=icon]/sidebar:size-8 md:group-data-[collapsible=icon]/sidebar:justify-center md:group-data-[collapsible=icon]/sidebar:p-0 md:group-data-[collapsible=icon]/sidebar:[&>span:last-child]:sr-only` + variant + size | `data-sidebar="menu-button" data-active="true\|false" aria-current="page"`; icon then `<span>` label (the span becomes `sr-only` when collapsed) |
| menu_action | `<button data-sidebar="menu-action">` | `absolute flex size-6 items-center justify-center rounded-md text-sidebar-foreground/70 outline-none hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring disabled:pointer-events-none disabled:opacity-50 md:group-data-[collapsible=icon]/sidebar:hidden [&_svg]:size-4` `top-1 right-1` | Needs `aria-label`; add `md:opacity-0 md:group-hover/menu-item:opacity-100 md:group-focus-within/menu-item:opacity-100` for show-on-hover |
| menu_badge | `<span data-sidebar="menu-badge">` | `pointer-events-none absolute top-1 right-1 flex h-6 min-w-6 items-center justify-center rounded-md px-1 text-xs tabular-nums text-sidebar-foreground/70 md:group-data-[collapsible=icon]/sidebar:hidden` |  |
| menu_sub | `<ul data-sidebar="menu-sub">` | `mx-3.5 flex min-w-0 flex-col gap-1 border-l border-sidebar-border px-2.5 py-1 md:group-data-[collapsible=icon]/sidebar:hidden` | Hidden when collapsed |
| menu_sub_item | `<li>` | `relative` |  |
| menu_sub_button | `<a data-sidebar="menu-sub-button">` | `flex min-w-0 items-center gap-2 rounded-md px-2 text-sidebar-foreground/70 outline-none hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-sidebar-ring aria-[current=page]:bg-sidebar-accent aria-[current=page]:text-sidebar-accent-foreground aria-disabled:pointer-events-none aria-disabled:opacity-50 [&>svg]:size-4 [&>svg]:shrink-0 [&>span:last-child]:truncate` + size |  |
| menu_skeleton | `<div aria-hidden="true" data-sidebar="menu-skeleton">` | `flex h-8 items-center gap-2 rounded-md px-2` + skeleton boxes | Placeholder row |
| input / separator | `<input data-sidebar="input">` / `<hr data-sidebar="separator">` | `bg-background md:group-data-[collapsible=icon]/sidebar:hidden` + input classes / separator classes |  |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### variant (on sidebar; default sidebar)

| Value | Aside classes | Panel classes |
|---|---|---|
| sidebar | `md:data-[collapsible=icon]:w-(--sidebar-width-icon)` | `md:shadow-none` |
| floating | `md:p-2 md:data-[collapsible=icon]:w-[calc(var(--sidebar-width-icon)+1rem+2px)] md:data-[collapsible=offcanvas]:px-0` | `md:rounded-xl md:border md:shadow-sm` |
| inset | `md:data-[collapsible=icon]:w-(--sidebar-width-icon)` `md:py-2` | `md:border-0 md:bg-transparent md:shadow-none` |

### collapsible (data-collapsible when collapsed; default offcanvas)

| Value | When `$sidebarOpen` is false |
|---|---|
| offcanvas | `data-collapsible="offcanvas"`: width 0, panel `invisible` |
| icon | `data-collapsible="icon"`: strip of `--sidebar-width-icon`; labels `sr-only`, group labels/badges/sub menus hidden |
| none | never collapses on desktop (omit the toggle) |

### side

| Value | Attribute / sheet side |
|---|---|
| left (default) | `data-side="left"`, sheet side `left` |
| right | `data-side="right"`, sheet side `right`; place the aside after the inset |

### menu_button variant

| Value | Classes |
|---|---|
| default | `border-transparent` |
| outline | `border-sidebar-border bg-sidebar shadow-xs` |

### menu_button size

| Value | Classes |
|---|---|
| sm | `h-7 text-xs` |
| md | `h-8 text-sm` |
| lg | `h-12 text-sm` |

## Accessibility

- The panel has `role="navigation"` and `aria-label="Sidebar"`; give distinct labels if several.
- Toggle buttons have `aria-label="Toggle sidebar"` and `aria-expanded` (`data-attr:aria-expanded="$sidebarOpen ? 'true' : 'false'"`).
- The current page link has `aria-current="page"` (and `data-active="true"` for styling).
- When collapsed to icons the label `<span>` becomes `sr-only` (still read) and the item keeps its `title`.
- **Mobile sheet is non-modal** (as upstream): no focus trap. Escape, the close button and a backdrop click close it (Datastar attributes below). If a trap is required, open it with `showModal()` instead and treat it as a `dialog`.

## Behaviour

State is two signals (names are page-global; rename when more than one sidebar):

| Signal | Meaning | Written by |
|---|---|---|
| `$sidebarOpen` | desktop expanded | desktop trigger and rail: `data-on:click="$sidebarOpen = !$sidebarOpen"` |
| `$sidebarMobileOpen` | mobile sheet open | mobile trigger `= true`; close button, backdrop, Escape `= false` |

The aside reflects them: `data-attr:data-state="$sidebarOpen ? 'expanded' : 'collapsed'"`, `data-attr:data-collapsible="$sidebarOpen ? '' : 'icon'"`; the dialog: `data-attr:open="$sidebarMobileOpen"`, `data-on:click="evt.target === el && ($sidebarMobileOpen = false)"`, `data-on:keydown="evt.key === 'Escape' && ($sidebarMobileOpen = false)"`.

All width/label/hiding rules are CSS keyed on `data-state`/`data-collapsible` (`group-data-[collapsible=icon]/sidebar:`). Verified in the gallery: trigger and rail collapse to a 3rem strip and expand again; the mobile sheet opens, closes by button, Escape and backdrop.

**Persisting the state (c, optional):** upstream keeps it in the caller. To remember it across loads, write a cookie from the handler (`document.cookie = 'sidebar=' + ($sidebarOpen ? '' : 'collapsed')`) and have the server render the initial `data-signals` value from it (the hand-built `app-shell.md` does this). Datastar Pro's `data-persist` is the alternative; it is not used because it is a paid attribute.

## Notes for a template author

- Parameters: variant, side, collapsible, signal names, the nav tree `(group label, items)`, which item is current.
- Slots: header, groups (menus, sub menus), footer, inset content, inset header.
- Needs the `--sidebar-*` tokens (tokens.css). Their values here are new (navy); fc-web did not install the sidebar.
- Icons: Lucide, inline.
- The big class strings repeat on every menu row; emit them as constants.
