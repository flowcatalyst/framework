@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.JsonBlock p
<pre class="${cx("fc-json", p.cls())}"$unsafe{attrs(p.attrs())}>${JsonPretty.print(p.value() == null ? tools.jackson.databind.node.NullNode.getInstance() : p.value())}</pre>
