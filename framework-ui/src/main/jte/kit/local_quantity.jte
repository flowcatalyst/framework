@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.LocalQuantity p
@if(nz(p.value()) == null)${or(p.empty(), "-")}@elseif(nz(p.unit()) == null)<data value="${p.value()}" data-init="${p.expr()}"><span data-ignore-morph>${p.value()}</span></data>@else<data value="${p.value()}" data-unit="${p.unit()}" data-init="${p.expr()}"><span data-ignore-morph>${p.value()}</span>${" " + p.unit()}</data>@endif
