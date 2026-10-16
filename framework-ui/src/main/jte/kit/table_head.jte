@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.TH p
@param gg.jte.Content body = null
<th class="${cx("h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground", p.cls())}" scope="col"$unsafe{attrs(p.attrs())}>@if(body != null)${body}@endif</th>
