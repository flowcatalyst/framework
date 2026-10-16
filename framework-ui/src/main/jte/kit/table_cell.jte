@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.TD p
@param gg.jte.Content body = null
<td class="${cx("p-3 align-middle whitespace-nowrap", p.cls())}" colspan="${pos(p.colspan())}"$unsafe{attrs(p.attrs())}>@if(body != null)${body}@endif</td>
