@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Badge p
@param gg.jte.Content body
@if(nz(p.href()) != null)<a href="${p.href()}" class="${cx("inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap", Classes.badgeVariant(p.variant()), p.cls())}"$unsafe{attrs(p.attrs())}>${body}</a>@else<span class="${cx("inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap", Classes.badgeVariant(p.variant()), p.cls())}"$unsafe{attrs(p.attrs())}>${body}</span>@endif
