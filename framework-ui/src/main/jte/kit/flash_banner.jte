@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FlashBanner p
<div role="${or(p.role(), "status")}" class="${"fc-banner fc-banner-" + Classes.oneOf(p.kind(), "success", "flash banner kind", "success", "error") + " mb-4"}" data-init__delay.5s="${p.autoDismiss() ? "el.remove()" : null}">${p.message()}</div>
