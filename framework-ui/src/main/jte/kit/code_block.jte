@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.CodeBlock p
@param gg.jte.Content body = null
<pre id="${nz(p.id()) != null ? id(p.id()) : null}" class="${cx("fc-code-block", p.cls())}" tabindex="0" aria-label="${p.label()}"><code>@if(body != null)${body}@endif</code></pre>
