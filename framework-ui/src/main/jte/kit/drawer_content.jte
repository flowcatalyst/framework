@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Drawer p
@param gg.jte.Content body = null
@param gg.jte.Content extras = null
@param gg.jte.Content footer = null
<button type="button" class="fc-icon-btn absolute top-[1.1rem] right-[1.1rem] z-10" aria-label="Close" data-on:click="${p.closeExpr()}">@template.kit.icon(name = "x", cls = "size-[1.2rem]")</button>
<header class="fc-drawer-header pr-14">
<div class="fc-drawer-titles">
<div class="min-w-0">
@if(nz(p.titleSignal()) != null)<h2 id="${nz(p.id()) != null ? id(p.id()) + "-title" : null}" tabindex="${nz(p.id()) != null ? "-1" : null}" class="fc-drawer-title" data-text="${"$" + signal(p.titleSignal())}"></h2>@else<h2 id="${nz(p.id()) != null ? id(p.id()) + "-title" : null}" tabindex="${nz(p.id()) != null ? "-1" : null}" class="fc-drawer-title">${p.title()}</h2>@endif
@if(nz(p.subtitleSignal()) != null)<p class="fc-drawer-subtitle" data-text="${"$" + signal(p.subtitleSignal())}"></p>@elseif(nz(p.subtitle()) != null)<p class="fc-drawer-subtitle">${p.subtitle()}</p>@endif
</div>
@if(extras != null)<div class="flex shrink-0 items-center gap-2">${extras}</div>@endif
</div>
</header>
<div id="${nz(p.bodyId())}" class="fc-drawer-body">@if(body != null)${body}@endif</div>
@if(footer != null)<div class="fc-drawer-footer">${footer}</div>@endif
