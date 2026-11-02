@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Progress p
<progress class="${cx("h-2 w-full appearance-none overflow-hidden rounded-full bg-foreground/10 [&::-webkit-progress-bar]:bg-transparent [&::-webkit-progress-value]:rounded-full [&::-webkit-progress-value]:bg-primary [&::-webkit-progress-value]:transition-all [&::-moz-progress-bar]:rounded-full [&::-moz-progress-bar]:bg-primary", p.cls())}" id="${nz(p.id()) == null ? null : id(p.id())}" value="${p.value() == null ? null : p.value().stripTrailingZeros().toPlainString()}" max="${(p.max() == null ? new java.math.BigDecimal(100) : p.max()).stripTrailingZeros().toPlainString()}" aria-label="${nz(p.label())}"></progress>
