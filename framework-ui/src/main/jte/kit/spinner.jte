@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Spinner p
@if(p.decorative())@template.kit.icon(name = "loader-circle", cls = cx("animate-spin", p.size()))@else@template.kit.icon(name = "loader-circle", cls = cx("animate-spin", or(p.size(), "size-6")), label = or(p.label(), "Loading"))@endif
