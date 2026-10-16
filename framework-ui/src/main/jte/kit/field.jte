@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Field p
@param gg.jte.Content body = null
!{var errorId = nz(p.errorId()) != null ? p.errorId() : p.forId() + "-err";}
<div role="group" data-slot="field" class="${cx("group/field flex min-w-0", Classes.fieldOrientation(p.orientation()), p.cls())}"$unsafe{attrs(p.attrs())}>
@if(nz(p.label()) != null)<label class="${cx("flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50", "leading-snug group-has-[:disabled]/field:opacity-50 group-has-[[aria-invalid=true]]/field:text-destructive group-data-[invalid=true]/field:text-destructive")}" for="${p.forId()}" data-slot="field-label">${p.label()}@if(p.required())<span class="text-destructive" aria-hidden="true">*</span>@endif</label>@endif
@if(body != null)${body}@endif
@if(nz(p.description()) != null)<p class="text-sm leading-relaxed text-muted-foreground [&_a]:underline [&_a]:underline-offset-4">${p.description()}</p>@endif
@if(nz(p.error()) != null)<div id="${errorId}" role="alert" class="text-sm text-destructive">${p.error()}</div>@endif
</div>
