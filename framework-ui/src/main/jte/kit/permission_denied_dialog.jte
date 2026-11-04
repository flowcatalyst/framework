@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.PermissionDeniedDialog p
!{var expired = p.expired();}
<dialog id="${id(p.id())}" class="fc-dialog w-[26rem] max-w-[calc(100vw-2rem)]" role="alertdialog" aria-labelledby="${p.id() + "-title"}" aria-describedby="${p.id() + "-desc"}"$unsafe{attrs(p.dialogAttrs())}>
<div class="fc-dialog-header"><span id="${p.id() + "-title"}">${expired ? Kit.t("kit.permission_denied.expired_title") : Kit.t("kit.permission_denied.denied_title")}</span></div>
<div class="fc-dialog-body flex flex-col items-center gap-3 text-center">
@if(expired)<span class="flex size-16 items-center justify-center rounded-full bg-yellow-500/15 text-yellow-600">@template.kit.icon(name = "clock", cls = "size-7")</span>@else<span class="flex size-16 items-center justify-center rounded-full bg-red-500/15 text-red-600">@template.kit.icon(name = "lock", cls = "size-7")</span>@endif
<p id="${p.id() + "-desc"}" class="text-[15px] leading-normal">${p.message()}</p>
@if(!expired && nz(p.requiredPermission()) != null)<p class="text-[13px] text-muted-foreground">${Kit.htmlMessage("kit.permission_denied.required", java.util.Map.of("permission", "<code class=\"rounded bg-foreground/10 px-1.5 py-0.5 text-xs\">" + Markup.escape(p.requiredPermission()) + "</code>"))}</p>@endif
@if(!expired)<p class="text-[13px] text-muted-foreground">${Kit.t("kit.permission_denied.contact_admin")}</p>@endif
</div>
<div class="fc-dialog-footer">
@if(expired)<a class="fc-btn fc-btn-primary" autofocus href="${url(p.login())}">${Kit.t("kit.permission_denied.log_in_again")}</a>@else<button type="button" class="fc-btn fc-btn-text" autofocus data-on:click="history.back()">${Kit.t("kit.permission_denied.go_back")}</button>
<a class="fc-btn fc-btn-primary" href="${url(p.home())}">${Kit.t("kit.permission_denied.go_dashboard")}</a>@endif
</div>
</dialog>
