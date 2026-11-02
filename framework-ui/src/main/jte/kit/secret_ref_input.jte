@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.SecretRefInput p
<div class="${cx("flex flex-col gap-2", p.cls())}">
<input type="text" class="${cx(Classes.INPUT, "font-mono")}" id="${id(p.id())}" name="${nz(p.name())}" value="${nz(p.value())}" placeholder="${or(p.placeholder(), "my-secret-name")}" pattern="${Props.SecretRefInput.PATTERN}" title="Lowercase letters, digits and single hyphens, with an optional store: prefix" autocomplete="off" autocapitalize="off" spellcheck="false" required="${p.required()}" disabled="${p.disabled()}" aria-invalid="${p.invalid() ? "true" : null}" aria-describedby="${p.describedByIds()}">
<p id="${id(p.id()) + "-help"}" class="${Classes.FIELD_DESC}">The name of a secret held in the secret store, such as <code>billing-api-key</code> or <code>vault:billing-api-key</code>. This is a reference, not the secret value: never type or paste the value here.</p>
</div>
