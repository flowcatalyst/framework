@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Diagram p
@param gg.jte.Content source = null
<fc-diagram class="${cx("block", p.cls())}" data-bundle="${url(p.bundle())}"><div class="fc-diagram-view overflow-auto rounded-md border border-border bg-card p-4 [&_svg]:mx-auto [&_svg]:block [&_svg]:h-auto [&_svg]:max-w-full" role="img" aria-label="${nz(p.label())}" hidden data-ignore></div><details class="mt-3 text-sm"><summary class="cursor-pointer text-muted-foreground">${Kit.t("kit.diagram.source")}</summary><pre class="fc-code-block mt-2" tabindex="0" aria-label="${Kit.t("kit.diagram.source_label", "label", p.label() == null ? "" : p.label())}"><code>@if(source != null)${source}@endif</code></pre></details></fc-diagram>
