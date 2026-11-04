@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.AlertDialog p
!{var confirm = Classes.oneOf(p.confirmVariant(), "destructive", "alert dialog confirm variant", "destructive", "primary"); var action = url(p.action());}
<dialog role="alertdialog" id="${id(p.id())}" aria-labelledby="${p.id() + "-title"}" aria-describedby="${p.id() + "-desc"}" class="${cx("fixed inset-0 z-50 size-full max-h-none max-w-none items-start justify-center overflow-y-auto bg-background/80 p-4 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]", p.cls())}">
<div class="relative my-auto flex w-full max-w-lg flex-col gap-4 rounded-xl border border-border bg-card p-6 text-card-foreground shadow-sm scale-95 opacity-0 in-[[open]]:scale-100 in-[[open]]:opacity-100 starting:in-[[open]]:scale-95 starting:in-[[open]]:opacity-0 [transition:scale_200ms_ease-out,opacity_200ms_ease-out]">
<div class="flex flex-col gap-1.5">
<h2 id="${p.id() + "-title"}" class="text-lg leading-none font-semibold">${p.title()}</h2>
<p id="${p.id() + "-desc"}" class="text-sm text-muted-foreground">${p.message()}</p>
</div>
@if(action != null)<form method="post" action="${action}">@if(p.hiddenFields() != null)@for(var h : p.hiddenFields())<input type="hidden" name="${h.name()}" value="${h.value()}">@endfor@endif<div class="flex flex-wrap items-center justify-end gap-2"><button class="${Classes.button("ghost", "md")}" type="button" autofocus data-on:click="${CLOSE_DIALOG}">${or(p.cancelLabel(), Kit.t("kit.dialog.cancel"))}</button><button class="${Classes.button(confirm, "md")}" type="submit">${or(p.confirmLabel(), Kit.t("kit.dialog.confirm"))}</button></div></form>@else<div class="flex flex-wrap items-center justify-end gap-2"><button class="${Classes.button("ghost", "md")}" type="button" autofocus data-on:click="${CLOSE_DIALOG}">${or(p.cancelLabel(), Kit.t("kit.dialog.cancel"))}</button><button class="${Classes.button(confirm, "md")}" type="submit">${or(p.confirmLabel(), Kit.t("kit.dialog.confirm"))}</button></div>@endif
</div>
</dialog>
