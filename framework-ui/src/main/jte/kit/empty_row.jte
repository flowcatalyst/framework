@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.EmptyRow p
<tr><td colspan="${p.colspan()}">@template.kit.empty_state(p = p.empty())</td></tr>
