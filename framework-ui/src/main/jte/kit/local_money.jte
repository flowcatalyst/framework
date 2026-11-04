@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.LocalMoney p
@if(nz(p.amount()) == null)${or(p.empty(), "-")}@else<data value="${p.amount()}" data-currency="${p.code()}" data-init="${p.expr()}">${p.amount() + " " + p.currency()}</data>@endif
