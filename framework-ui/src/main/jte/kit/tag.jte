@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Tag p
<span class="${"fc-tag fc-tag-" + Classes.oneOf(p.severity(), "secondary", "tag severity", "success", "info", "warn", "danger", "secondary", "primary")}">${p.label()}</span>
