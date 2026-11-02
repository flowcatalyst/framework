@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.RadioGroup p
<div role="radiogroup" aria-label="${nz(p.label())}" aria-labelledby="${nz(p.labelledBy())}" class="${cx("grid gap-3", p.cls())}">
@for(var o : p.optionList())<div class="flex items-center gap-2"><span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="radio" class="peer size-4 shrink-0 appearance-none rounded-full border border-input bg-background transition-colors outline-none checked:border-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="${id(o.id())}" name="${p.name()}" value="${o.value()}" checked="${o.value().equals(p.value())}" disabled="${o.disabled()}"><span class="pointer-events-none absolute inset-0 m-auto size-2 rounded-full bg-primary opacity-0 transition-opacity peer-checked:opacity-100"></span></span><label class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50" for="${o.id()}">${o.label()}</label></div>
@endfor
</div>
