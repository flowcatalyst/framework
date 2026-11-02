@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Tooltip p
@param gg.jte.Content trigger = null
<span class="${cx("group relative inline-flex", p.cls())}">@if(trigger != null)${trigger}@endif<span id="${id(p.id())}" role="tooltip" class="pointer-events-none invisible absolute bottom-full left-1/2 z-50 mb-2 -translate-x-1/2 rounded-md bg-foreground px-2.5 py-1 text-xs font-medium text-background opacity-0 shadow-sm whitespace-nowrap [transition:opacity_150ms_ease-out,visibility_150ms_allow-discrete] group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100">${p.text()}</span></span>
