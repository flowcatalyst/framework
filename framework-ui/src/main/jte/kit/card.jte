@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Card p
@param gg.jte.Content body = null
@param gg.jte.Content footer = null
!{int level = p.level(); boolean hasTitle = nz(p.title()) != null; boolean hasDesc = nz(p.description()) != null;}
<div class="${cx("flex flex-col gap-5 rounded-xl border border-border bg-card py-6 text-card-foreground shadow-sm", p.cls())}">
@if(hasTitle || hasDesc)<div class="flex flex-col gap-1.5 px-6">@if(hasTitle)@if(level == 1)<h1 class="text-base leading-none font-semibold">${p.title()}</h1>@elseif(level == 2)<h2 class="text-base leading-none font-semibold">${p.title()}</h2>@elseif(level == 3)<h3 class="text-base leading-none font-semibold">${p.title()}</h3>@elseif(level == 4)<h4 class="text-base leading-none font-semibold">${p.title()}</h4>@elseif(level == 5)<h5 class="text-base leading-none font-semibold">${p.title()}</h5>@else<h6 class="text-base leading-none font-semibold">${p.title()}</h6>@endif@endif@if(hasDesc)<p class="text-sm text-muted-foreground">${p.description()}</p>@endif</div>@endif
@if(body != null)<div class="px-6">${body}</div>@endif
@if(footer != null)<div class="flex items-center gap-2 px-6">${footer}</div>@endif
</div>
