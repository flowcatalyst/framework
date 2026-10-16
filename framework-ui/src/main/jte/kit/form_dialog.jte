@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.FormDialog p
@param gg.jte.Content body = null
@template.kit.dialog(p = new Props.Dialog(p.id(), p.title(), p.description(), null, p.width(), true, false, null, p.dialogAttrs()), body = @`
<form id="${Props.FormDialog.formId(p.id())}" method="post" action="${url(p.action())}" class="flex flex-col gap-4" data-signals="${p.signalsExpr()}" data-indicator="${p.busyName()}" data-init="${"el.dataset.snapshot = " + Props.FormDialog.SNAPSHOT}" data-on:input="${p.dirtyExpr()}" data-on:submit="${SUBMIT_FORM}" data-attr:aria-busy="${p.ariaBusyExpr()}">
@template.kit.form_dialog_error(dialogId = p.id(), message = p.error())
@if(p.hidden() != null)@for(var h : p.hidden())<input type="hidden" name="${h.name()}" value="${h.value()}"$unsafe{attrs(h.attrs())}>@endfor@endif
@if(body != null)${body}@endif
</form>
<dialog id="${p.id() + "-discard"}" role="alertdialog" class="fc-dialog w-[28rem] max-w-[calc(100vw-2rem)]" aria-labelledby="${p.id() + "-discard-title"}" aria-describedby="${p.id() + "-discard-desc"}" data-on:click="${LIGHT_DISMISS}">
<div class="fc-dialog-header"><span id="${p.id() + "-discard-title"}">Discard Changes</span></div>
<div class="fc-dialog-body flex items-start gap-3 text-subtle-foreground">
@template.kit.icon(name = "triangle-alert", cls = "size-6 mt-0.5 shrink-0 text-red-600")
<p id="${p.id() + "-discard-desc"}">You have unsaved changes. Discard them?</p>
</div>
<div class="fc-dialog-footer">
<button type="button" class="fc-btn fc-btn-text" autofocus data-on:click="${CLOSE_DIALOG}">Keep Editing</button>
<button type="button" class="fc-btn fc-btn-danger" data-on:click="${"el.closest('dialog').close(); document.getElementById('" + id(p.id()) + "').close()"}">Discard</button>
</div>
</dialog>
`, footer = @`
<button type="button" class="${Classes.button("ghost", "md")}" data-attr:disabled="${p.busy()}" data-on:click="${p.dismissExpr()}">Cancel</button>
<button type="submit" form="${Props.FormDialog.formId(p.id())}" class="${Classes.button("primary", "md")}" disabled="${p.requireChange()}" data-attr:disabled="${p.submitDisabledExpr()}">${p.submitLabel()}</button>
`)
