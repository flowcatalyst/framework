@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.AutocompleteOptions p
<datalist id="${id(p.id())}">@if(p.options() != null)@for(var o : p.options())<option value="${o.value()}">${o.label()}</option>@endfor@endif</datalist>
