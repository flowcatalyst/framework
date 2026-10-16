@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Sheet p
@param gg.jte.Content body = null
@param gg.jte.Content footer = null
!{var side = Classes.oneOf(p.side(), "right", "sheet side", "right", "left", "top", "bottom");}
<dialog id="${id(p.id())}" aria-labelledby="${p.id() + "-title"}" data-on:click="${LIGHT_DISMISS}" class="fixed inset-0 z-50 size-full max-h-none max-w-none overflow-hidden bg-background/80 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]">
<div class="${cx("flex flex-col gap-4 overflow-y-auto border-border bg-card p-6 text-card-foreground shadow-sm [transition:translate_200ms_ease-out]", side.equals("left") ? "mr-auto h-full w-full max-w-sm border-r -translate-x-full in-[[open]]:translate-x-0 starting:in-[[open]]:-translate-x-full" : side.equals("top") ? "mb-auto max-h-full w-full border-b -translate-y-full in-[[open]]:translate-y-0 starting:in-[[open]]:-translate-y-full" : side.equals("bottom") ? "mt-auto max-h-full w-full border-t translate-y-full in-[[open]]:translate-y-0 starting:in-[[open]]:translate-y-full" : "ml-auto h-full w-full max-w-sm border-l translate-x-full in-[[open]]:translate-x-0 starting:in-[[open]]:translate-x-full", p.cls())}">
<div class="flex flex-col gap-1.5">
<h2 id="${p.id() + "-title"}" class="text-lg leading-none font-semibold">${p.title()}</h2>
@if(nz(p.description()) != null)<p class="text-sm text-muted-foreground">${p.description()}</p>@endif
</div>
<div class="flex-1">@if(body != null)${body}@endif</div>
@if(footer != null)<div class="flex flex-wrap items-center justify-end gap-2">${footer}</div>@endif
</div>
</dialog>
