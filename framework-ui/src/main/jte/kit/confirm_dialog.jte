@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.ConfirmDialog p
!{var tone = Classes.oneOf(p.tone(), "default", "confirm tone", "default", "danger", "warn");}
<dialog id="${id(p.id())}" class="fc-dialog w-[28rem] max-w-[calc(100vw-2rem)]" closedby="any" aria-labelledby="${p.id() + "-title"}" data-on:click="${LIGHT_DISMISS}">
<form method="post" action="${url(p.action())}"$unsafe{attrs(p.formAttrs())}>
<div class="fc-dialog-header"><span id="${p.id() + "-title"}">${p.title()}</span></div>
<div class="fc-dialog-body flex items-start gap-3 text-subtle-foreground">
@template.kit.icon(name = "triangle-alert", cls = tone.equals("danger") ? "size-6 mt-0.5 shrink-0 text-red-600" : "size-6 mt-0.5 shrink-0 text-amber-600")
<p>${p.message()}</p>
</div>
@if(p.hidden() != null)@for(var h : p.hidden())<input type="hidden" name="${h.name()}" value="${h.value()}"$unsafe{attrs(h.attrs())}>@endfor@endif
<div class="fc-dialog-footer">
<button type="button" class="fc-btn fc-btn-text" autofocus data-on:click="${CLOSE_DIALOG}">Cancel</button>
<button type="submit" class="${tone.equals("danger") ? "fc-btn fc-btn-danger" : tone.equals("warn") ? "fc-btn fc-btn-warn" : "fc-btn fc-btn-primary"}">${p.confirmLabel()}</button>
</div>
</form>
</dialog>
