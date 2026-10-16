@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.LegacyButton p
@param gg.jte.Content body = null
!{var classes = cx("fc-btn fc-btn-" + Classes.oneOf(p.variant(), "primary", "legacy button variant", "primary", "primary-outline", "secondary", "outline", "warn-outline", "danger-outline", "danger", "warn", "text", "text-primary", "link") + (p.small() ? " fc-btn-sm" : "") + (p.block() ? " fc-btn-block" : ""), p.cls());}
@if(nz(p.href()) != null)<a href="${p.disabled() ? null : p.href()}" aria-disabled="${p.disabled() ? "true" : null}" class="${classes}"$unsafe{attrs(p.attrs())}>@if(nz(p.icon()) != null)@template.kit.icon(name = p.icon(), cls = "size-[1rem]")@endif@if(body != null)${body}@endif</a>@else<button type="${or(p.type(), "button")}" class="${classes}" disabled="${p.disabled()}"$unsafe{attrs(p.attrs())}>@if(nz(p.icon()) != null)@template.kit.icon(name = p.icon(), cls = "size-[1rem]")@endif@if(body != null)${body}@endif</button>@endif
