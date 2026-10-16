@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Drawer p
@param gg.jte.Content body = null
@param gg.jte.Content extras = null
@param gg.jte.Content footer = null
<aside id="${nz(p.id()) != null ? id(p.id()) : null}" class="${cx("fc-drawer", "wide".equals(p.size()) ? "fc-drawer-wide" : "two-thirds".equals(p.size()) ? "fc-drawer-two-thirds" : null)}" role="complementary" aria-label="${p.label()}" data-drawer style="display: none" data-show="${p.showExpr()}" data-on:keydown__window="${p.escapeExpr()}">
@template.kit.drawer_content(p = p, body = body, extras = extras, footer = footer)
</aside>
