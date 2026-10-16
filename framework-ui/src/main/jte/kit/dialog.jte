@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Dialog p
@param gg.jte.Content body = null
@param gg.jte.Content footer = null
!{var dark = "dark".equals(Classes.oneOf(p.mask(), "frosted", "dialog mask", "frosted", "dark")); var w = Classes.oneOf(p.width(), "default", "dialog width", "default", "narrow", "wide");}
<dialog id="${id(p.id())}" role="${nz(p.role())}" aria-labelledby="${p.id() + "-title"}" aria-describedby="${nz(p.description()) != null ? p.id() + "-desc" : null}" data-on:click="${p.persistent() ? null : LIGHT_DISMISS}" class="${dark ? cx("fixed inset-0 z-50 size-full max-h-none max-w-none items-start justify-center overflow-y-auto bg-background/80 p-4 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]", "[&]:w-screen [&]:bg-[rgb(15_23_42/0.4)] [&]:backdrop-blur-none backdrop:bg-transparent") : "fixed inset-0 z-50 size-full max-h-none max-w-none items-start justify-center overflow-y-auto bg-background/80 p-4 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]"}"$unsafe{attrs(p.attrs())}>
<div class="${cx("relative my-auto flex w-full max-w-lg flex-col gap-4 rounded-xl border border-border bg-card p-6 text-card-foreground shadow-sm scale-95 opacity-0 in-[[open]]:scale-100 in-[[open]]:opacity-100 starting:in-[[open]]:scale-95 starting:in-[[open]]:opacity-0 [transition:scale_200ms_ease-out,opacity_200ms_ease-out]", "narrow".equals(w) ? "[&]:max-w-[450px]" : "wide".equals(w) ? "[&]:max-w-[700px]" : null)}">
<div class="flex flex-col gap-1.5">
<h2 id="${p.id() + "-title"}" class="text-lg leading-none font-semibold">${p.title()}</h2>
@if(nz(p.description()) != null)<p id="${p.id() + "-desc"}" class="text-sm text-muted-foreground">${p.description()}</p>@endif
</div>
@if(p.closable())<button type="button" class="${cx(Classes.button("ghost", "icon"), "absolute top-3 right-3 [&]:size-8 [&]:rounded-full text-muted-foreground")}" aria-label="Close" data-on:click="${CLOSE_DIALOG}">@template.kit.icon(name = "x", cls = "size-[1.1rem]")</button>@endif
@if(body != null)${body}@endif
@if(footer != null)<div class="flex flex-wrap items-center justify-end gap-2">${footer}</div>@endif
</div>
</dialog>
