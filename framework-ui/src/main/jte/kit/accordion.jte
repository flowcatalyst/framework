@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Accordion p
@param java.util.Map<String, gg.jte.Content> panels
<div class="${cx("w-full", p.cls())}">
@for(var it : p.itemList())<details class="group border-b border-border last:border-b-0 [interpolate-size:allow-keywords] [&::details-content]:h-0 [&::details-content]:overflow-hidden [&::details-content]:[transition:height_200ms_ease-out,content-visibility_200ms_allow-discrete] [&[open]::details-content]:h-auto" name="${nz(p.name())}" open="${id(it.value()).equals(p.open())}"><summary class="flex w-full cursor-pointer list-none items-center justify-between gap-4 py-4 text-left text-sm font-medium outline-none transition-colors hover:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background [&::-webkit-details-marker]:hidden">${it.title()}@template.kit.icon(name = "chevron-down", cls = "size-4 shrink-0 text-muted-foreground transition-transform duration-200 ease-out group-open:rotate-180")</summary><div class="pb-4 text-sm text-muted-foreground">@if(panels.get(it.value()) != null)${panels.get(it.value())}@endif</div></details>
@endfor
</div>
