@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param String name
@param String cls = null
@param String label = null
<svg width="1em" height="1em" class="${nz(cls)}" role="${label != null ? "img" : null}" aria-label="${label}" aria-hidden="${label == null ? "true" : null}"><use href="${Kit.iconHref(name)}"></use></svg>
