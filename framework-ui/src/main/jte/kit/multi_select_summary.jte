@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.MultiSelect p
<span id="${Props.MultiSelect.summaryId(p.id())}" class="fc-multiselect-summary">${p.summary()}</span>
