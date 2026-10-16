@import io.flowcatalyst.framework.ui.kit.*
@import static io.flowcatalyst.framework.ui.kit.Markup.*
@param Props.Document p
@param gg.jte.Content body
<!DOCTYPE html>
<html lang="en"$unsafe{attrs(p.htmlAttrs())}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${p.title()}</title>
<link rel="icon" href="data:,">
<link rel="stylesheet" href="${p.cssUrl()}">
<script type="module" src="${p.jsUrl()}"></script>
</head>
<body data-on:datastar-fetch="${p.sessionExpiredExpr()}">
${body}
</body>
</html>
