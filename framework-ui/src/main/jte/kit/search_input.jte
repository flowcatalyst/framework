@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.SearchInput p
<div class="${nz(p.cls()) != null ? "fc-input-icon " + p.cls() : "fc-input-icon w-80 max-w-full"}">
@template.kit.icon(name = "search", cls = "size-[1rem]")
<input type="search" name="${or(p.name(), "q")}" class="fc-input" placeholder="${p.placeholder()}" aria-label="${p.placeholder()}" value="${nz(p.value())}"$unsafe{attrs(p.attrs())}>
</div>
