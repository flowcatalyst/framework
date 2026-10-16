@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.AppShell p
<aside id="fc-sidebar" class="fc-sidebar">
<div class="fc-sidebar-header">
<a href="${url(p.logoHref())}" class="fc-sidebar-logo" style="height: 32px"><span style="width: 32px; height: 32px">@template.kit.logo()</span></a>
<button type="button" id="fc-sidebar-close" class="fc-sidebar-close" aria-label="Close navigation" data-on:click="${AppShells.MOBILE_CLOSE}">@template.kit.icon(name = "x", cls = "size-[1.25rem]")</button>
<button type="button" class="fc-collapse-btn" aria-label="Collapse sidebar" aria-expanded="true" data-attr:aria-expanded="$sidebarCollapsed ? 'false' : 'true'" data-on:click="${AppShells.COLLAPSE}">@template.kit.icon(name = "chevron-left", cls = "size-[1rem]")</button>
</div>
<nav class="fc-sidebar-nav" aria-label="Main">
@if(p.nav() != null)@for(var g : p.nav())<div class="fc-nav-group">
<span class="fc-nav-group-label">${g.label()}</span>
@if(g.items() != null)@for(var it : g.items())@if(it.children() != null && !it.children().isEmpty())<details open="${it.open()}">
<summary class="fc-nav-item" title="${it.label()}">@template.kit.icon(name = it.icon())<span class="fc-nav-label">${it.label()}</span>@template.kit.icon(name = "chevron-right", cls = "fc-nav-chevron")</summary>
<div class="fc-nav-children">
@for(var c : it.children())<a href="${url(c.href())}" class="fc-nav-child" aria-current="${c.current() ? "page" : null}">@template.kit.icon(name = c.icon())<span class="fc-nav-label">${c.label()}</span></a>@endfor
</div>
</details>@else<a href="${url(it.href())}" class="fc-nav-item" title="${it.label()}" aria-current="${it.current() ? "page" : null}">@template.kit.icon(name = it.icon())<span class="fc-nav-label">${it.label()}</span></a>@endif@endfor@endif
</div>@endfor@endif
</nav>
<div class="fc-sidebar-footer">
<button type="button" class="fc-user-trigger" popovertarget="fc-user-menu" aria-label="Account menu">
<span class="fc-avatar">${p.user().initials()}</span>
<span class="fc-user-text min-w-0 flex-1"><span class="fc-user-name">${p.user().name()}</span><span class="fc-user-email">${p.user().email()}</span></span>
@template.kit.icon(name = "chevron-up", cls = "shrink-0 text-white/50 fc-user-text size-[11px]")
</button>
</div>
</aside>
