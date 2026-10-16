@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FlashMessage p
<div class="${"fc-flash fc-flash-" + Flash.kind(p.kind())}" role="${Flash.isAlert(p.kind()) ? "alert" : null}" data-init__delay.3s="${"3s".equals(Flash.lifetime(p.kind(), p.persistent())) ? "el.remove()" : null}" data-init__delay.5s="${"5s".equals(Flash.lifetime(p.kind(), p.persistent())) ? "el.remove()" : null}"$unsafe{attrs(p.attrs())}>
@template.kit.icon(name = Flash.icon(p.kind()), cls = "fc-flash-icon")
<div class="fc-flash-body"><span class="fc-flash-summary">${p.summary()}</span>@if(nz(p.detail()) != null)<span class="fc-flash-detail">${p.detail()}</span>@endif</div>
<button type="button" class="fc-flash-dismiss" aria-label="Dismiss notification" data-on:click="el.parentElement.remove()">@template.kit.icon(name = "x", cls = "size-[0.9rem]")</button>
</div>
