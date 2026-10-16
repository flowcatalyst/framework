@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param String dialogId
@param String message = null
<div id="${Props.FormDialog.errorId(dialogId)}">@if(nz(message) != null)@template.kit.alert(p = new Props.Alert("destructive", "alert", "circle-x", message, null, null, null))@endif</div>
