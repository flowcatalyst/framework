@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.CodeEditor p
<fc-code-editor class="${cx("block", p.cls())}" language="${nz(p.language())}" readonly="${p.readOnly()}"><textarea class="${cx(Classes.TEXTAREA, "font-mono")}" id="${nz(p.id())}" name="${nz(p.name())}" aria-label="${nz(p.label())}" rows="${pos(p.rows())}" readonly="${p.readOnly()}" spellcheck="false" aria-invalid="${p.invalid() ? "true" : null}" aria-describedby="${nz(p.describedBy())}"$unsafe{attrs(p.attrs())}>${p.value()}</textarea></fc-code-editor>
