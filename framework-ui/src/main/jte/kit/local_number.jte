@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.LocalNumber p
@if(nz(p.value()) == null)${or(p.empty(), "-")}@else<data value="${p.value()}" data-init="${p.expr()}"><span data-ignore-morph>${p.value()}</span></data>@endif
