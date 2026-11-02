@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Kbd p
!{var keys = p.keys() == null ? java.util.List.<String>of() : p.keys();}
@if(keys.size() == 1)<kbd class="${cx("inline-flex h-5 w-fit min-w-5 shrink-0 items-center justify-center gap-1 rounded-sm border border-border bg-foreground/5 px-1.5 font-sans text-xs font-medium text-muted-foreground", p.cls())}">${keys.get(0)}</kbd>@elseif(keys.size() > 1)<span class="${cx("inline-flex items-center gap-1 whitespace-nowrap", p.cls())}">@for(var k : keys)<kbd class="inline-flex h-5 w-fit min-w-5 shrink-0 items-center justify-center gap-1 rounded-sm border border-border bg-foreground/5 px-1.5 font-sans text-xs font-medium text-muted-foreground">${k}</kbd>@endfor</span>@endif
