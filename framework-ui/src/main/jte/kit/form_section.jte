@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FormSection p
@param gg.jte.Content body = null
@param gg.jte.Content actions = null
<section class="${cx("fc-form-section", p.danger() ? "fc-danger-zone" : null, p.card() ? "fc-card" : null)}">
<header class="fc-section-header">
<div><h3 class="fc-section-title">${p.title()}</h3>@if(nz(p.description()) != null)<p class="fc-section-description">${p.description()}</p>@endif</div>
@if(actions != null)${actions}@endif
</header>
<div class="fc-section-body">@if(body != null)${body}@endif</div>
</section>
