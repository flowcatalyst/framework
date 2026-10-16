@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.IconButton p
<button type="${or(p.type(), "button")}" class="${cx("fc-icon-btn", p.cls())}" aria-label="${p.label()}"$unsafe{attrs(p.attrs())}>@template.kit.icon(name = p.icon(), cls = "size-[1rem]")</button>
