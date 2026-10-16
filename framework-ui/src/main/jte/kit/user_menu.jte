@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.AppShell p
<div id="fc-user-menu" popover class="fc-user-menu">
<div class="flex items-center gap-3 p-4">
<span class="fc-avatar fc-avatar-lg">${p.user().initials()}</span>
<div class="min-w-0"><p class="truncate text-[15px] font-semibold">${p.user().name()}</p><p class="truncate text-[13px] text-muted-foreground">${p.user().email()}</p></div>
</div>
<div class="border-t border-border p-2">
<a href="${url(p.profileHref())}" class="fc-menu-item">@template.kit.icon(name = "user", cls = "size-[1.1rem]") Profile</a>
@if(nz(p.resetPasswordHref()) != null)<a href="${url(p.resetPasswordHref())}" class="fc-menu-item">@template.kit.icon(name = "key-round", cls = "size-[1.1rem]") Reset Password</a>@endif
</div>
@if(nz(p.theme()) != null)<div class="border-t border-border p-2">@template.kit.theme_choices(current = p.theme())</div>@endif
<div class="border-t border-border p-2">
<form method="post" action="${url(p.signOutAction())}"$unsafe{attrs(p.signOutAttrs())}>
<button type="submit" class="fc-menu-item danger">@template.kit.icon(name = "log-out", cls = "size-[1.1rem]") Sign Out</button>
</form>
</div>
<div class="flex justify-between border-t border-border px-4 py-2.5 text-xs text-muted-foreground"><span>Version</span><span>${p.version()}</span></div>
</div>
