@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.PasswordInput p
<div class="${cx("fc-password", p.cls())}" data-signals="${p.signals()}">
<div class="relative">
<input type="password" class="${cx(Classes.INPUT, "pr-10")}" id="${id(p.id())}" name="${nz(p.name())}" autocomplete="${or(p.autocomplete(), "new-password")}" placeholder="${nz(p.placeholder())}" required="${p.required()}" disabled="${p.disabled()}" aria-invalid="${p.invalid() ? "true" : null}" aria-describedby="${p.describedByIds()}" data-attr:type="${p.shown() + " ? 'text' : 'password'"}" data-on:input="${p.meter() ? p.scoreExpr() : null}">
<button type="button" class="fc-password-toggle" aria-label="Show password" aria-controls="${id(p.id())}" disabled="${p.disabled()}" data-attr:aria-label="${p.shown() + " ? 'Hide password' : 'Show password'"}" data-on:click="${p.shown() + " = !" + p.shown()}">
<span data-show="${"!" + p.shown()}">@template.kit.icon(name = "eye", cls = "size-4")</span>
<span data-show="${p.shown()}" style="display: none">@template.kit.icon(name = "eye-off", cls = "size-4")</span>
</button>
</div>
@if(p.meter())<div class="fc-password-meter" aria-hidden="true" data-score="0" data-attr:data-score="${p.scoreAttr()}"><span></span><span></span><span></span><span></span></div>
<p id="${id(p.id()) + "-strength"}" class="fc-password-strength" aria-live="polite" data-text="${p.strengthText()}"></p>
<p class="fc-password-help">The meter is advice only; the password policy is checked when you save.</p>@endif
</div>
