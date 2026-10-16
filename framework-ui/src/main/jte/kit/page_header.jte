@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.PageHeader p
@param gg.jte.Content actions = null
<header class="fc-page-header">
<div><h1 class="fc-page-title">${p.title()}</h1>@if(nz(p.subtitle()) != null)<p class="fc-page-subtitle">${p.subtitle()}</p>@endif</div>
<div class="flex items-center gap-2">@if(actions != null)${actions}@endif</div>
</header>
