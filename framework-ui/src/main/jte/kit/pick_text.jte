@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.PickItem it
<span class="flex min-w-0 flex-col">
<span class="truncate font-medium">${it.name()}</span>
<span class="truncate font-mono text-[12px] text-muted-foreground">${it.code()}</span>
@if(nz(it.note()) != null)<span class="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">${it.note()}</span>@endif
</span>
