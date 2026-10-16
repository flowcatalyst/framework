@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.ToggleGroup p
!{var type = "exclusive".equals(p.kind()) ? "radio" : "checkbox";}
<div class="inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1"$unsafe{attrs(p.attrs())}>
@if(p.options() != null)@for(var o : p.options())<label class="${cx("inline-flex shrink-0 cursor-pointer items-center justify-center border border-transparent text-sm font-medium whitespace-nowrap transition-colors select-none text-muted-foreground hover:bg-foreground/5 hover:text-foreground has-[:checked]:bg-foreground/10 has-[:checked]:text-foreground has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring has-[:focus-visible]:ring-offset-2 has-[:focus-visible]:ring-offset-background has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50", Classes.toggleSize(p.size()))}"><input type="${type}" name="${p.name()}" value="${o.value()}" class="sr-only" checked="${p.selected() != null && p.selected().contains(o.value())}"$unsafe{attrs(p.inputAttrs())}>${o.label()}</label>@endfor@endif
</div>
