@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Button p
@param gg.jte.Content body = null
@if(nz(p.href()) != null)@if(p.disabled())<a class="${cx(Classes.button(p.variant(), p.size()), "pointer-events-none opacity-50", p.cls())}" aria-disabled="true" tabindex="-1"$unsafe{attrs(p.attrs())}>@if(nz(p.icon()) != null)@template.kit.icon(name = p.icon())@endif@if(body != null)${body}@endif</a>@else<a href="${p.href()}" class="${cx(Classes.button(p.variant(), p.size()), p.cls())}"$unsafe{attrs(p.attrs())}>@if(nz(p.icon()) != null)@template.kit.icon(name = p.icon())@endif@if(body != null)${body}@endif</a>@endif@else<button class="${cx(Classes.button(p.variant(), p.size()), p.cls())}" type="${or(p.type(), "button")}" disabled="${p.disabled()}"$unsafe{attrs(p.attrs())}>@if(nz(p.icon()) != null)@template.kit.icon(name = p.icon())@endif@if(body != null)${body}@endif</button>@endif
