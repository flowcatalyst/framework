@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Markdown p
@param String html
<div class="${cx("fc-markdown", p.cls())}">$unsafe{html}</div>
