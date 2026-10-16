@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FieldGroup p
@param gg.jte.Content body = null
<div class="${cx("@container/field-group flex flex-col gap-5", p.cls())}">@if(body != null)${body}@endif</div>
