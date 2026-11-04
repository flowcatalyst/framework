@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.SecretRefInput p
<div class="${cx("flex flex-col gap-2", p.cls())}">
<input type="text" class="${cx(Classes.INPUT, "font-mono")}" id="${id(p.id())}" name="${nz(p.name())}" value="${nz(p.value())}" placeholder="${or(p.placeholder(), Kit.t("kit.secretref.placeholder"))}" pattern="${Props.SecretRefInput.PATTERN}" title="${Kit.t("kit.secretref.title")}" autocomplete="off" autocapitalize="off" spellcheck="false" required="${p.required()}" disabled="${p.disabled()}" aria-invalid="${p.invalid() ? "true" : null}" aria-describedby="${p.describedByIds()}">
<p id="${id(p.id()) + "-help"}" class="${Classes.FIELD_DESC}">${Kit.htmlMessage("kit.secretref.help", java.util.Map.of("example", "<code>billing-api-key</code>", "vaultExample", "<code>vault:billing-api-key</code>"))}</p>
</div>
