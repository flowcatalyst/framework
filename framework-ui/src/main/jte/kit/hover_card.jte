@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.HoverCard p
@param gg.jte.Content trigger = null
@param gg.jte.Content body = null
<span class="${cx("group relative inline-flex", p.cls())}">@if(trigger != null)${trigger}@endif<span id="${id(p.id())}" class="flex flex-col gap-2 invisible absolute top-full left-0 z-50 mt-2 w-64 rounded-lg border border-border bg-popover p-4 text-popover-foreground opacity-0 shadow-sm [transition:opacity_150ms_ease-out_300ms,visibility_150ms_allow-discrete_300ms] group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100">@if(body != null)${body}@endif</span></span>
