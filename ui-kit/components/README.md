# Component specs

Read `_conventions.md` first. One file per component; each has purpose, exact HTML, parts, options, accessibility, behaviour (with mechanism) and notes for a template author.

| Component | Source | Mechanism |
|---|---|---|
| [Accordion](accordion.md) | upstream Topcoat UI registry 0.9.0 | (a) native `<details>` |
| [Alert](alert.md) | fc-web themed copy | (a) native |
| [Alert dialog](alert-dialog.md) | fc-web themed copy | (b) Datastar attributes opening a native modal `<dialog role="alertdialog">` |
| [App shell (hand-built navy sidebar + user menu)](app-shell.md) | hand-built | (b) Datastar signal on `<html>` |
| [Avatar](avatar.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Autocomplete (server-searched input; client select)](autocomplete.md) | new (Go platform port) | (a) `<input list>` + `<datalist>`; (c) server-refreshed suggestions |
| [Badge](badge.md) | fc-web themed copy | (a) native |
| [Breadcrumb](breadcrumb.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Button](button.md) | fc-web themed copy | (a) native |
| [Card](card.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Checkbox](checkbox.md) | fc-web themed copy | (a) native |
| [Code block (highlighted code, copy)](code-block.md) | new (Go platform port) | (a) native; (b) copy |
| [Code chips](code-chips.md) | hand-built | (a) native |
| [Confirm dialog (hand-built)](confirm-dialog.md) | hand-built | (b) Datastar opens/closes a native modal `<dialog>` |
| [Cursor pager](cursor-pager.md) | hand-built | (a) native links |
| [Data table / list page (composition)](data-table.md) | composition of `table`, `table-toolbar`, `paginator`, `empty-state`, `drawer`, `page-header` | (b) client-side filter/sort/drawer |
| [Date range (two native date inputs)](date-range.md) | new (Go platform port) | (a) native; (b) bounds on focus |
| [Detail field / detail value (FcDetailField)](detail-field.md) | hand-built | (a) native |
| [Dialog](dialog.md) | fc-web themed copy | (b) Datastar attributes opening a native modal `<dialog>` |
| [Dirty form (Save enabled on change)](dirty-form.md) | hand-built behaviour | (b) Datastar |
| [Drawer (record drawer)](drawer.md) | hand-built | (b) Datastar signal |
| [Dropdown menu](dropdown-menu.md) | fc-web themed copy | (a) native `<details>` |
| [Empty state](empty-state.md) | hand-built | (a) native |
| [Field (form layout)](field.md) | fc-web themed copy | (a) native |
| [Filter select](filter-select.md) | hand-built | (a) native |
| [Flash banner](flash-banner.md) | hand-built | (a) native |
| [Flash stack (notification banners)](flash-stack.md) | port of the SPA's NotificationBannerStack + new request-failure fallback | (c) server patches; (b) dismiss and fallback |
| [Form field (FcFormField)](form-field.md) | hand-built | (a) native |
| [Form dialog (create/edit form in a modal)](form-dialog.md) | new (Go platform port) | (b) modal, dirty and busy state, discard question; (c) server validation as patches; (a) native post |
| [Form section (FcFormSection)](form-section.md) | hand-built | (a) native |
| [Hover card](hover-card.md) | upstream Topcoat UI registry 0.9.0 | (a) CSS only |
| [Input](input.md) | fc-web themed copy | (a) native |
| [JSON block](json-block.md) | hand-built | (a) native |
| [Kbd](kbd.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Label](label.md) | fc-web themed copy | (a) native |
| [Legacy button (.fc-btn) and icon button (.fc-icon-btn)](legacy-fc-btn.md) | hand-built | (a) native |
| [Local time](local-time.md) | hand-built | (b) Datastar `data-init` |
| [Multi-select (checklist with search)](multi-select.md) | new (Go platform port) | (a) `<details>` + checkboxes; (b) search; (c) summary/options patched |
| [Page header](page-header.md) | hand-built | (a) native |
| [Pagination](pagination.md) | fc-web themed copy | (a) native (links) |
| [Paginator](paginator.md) | hand-built | (a) native links |
| [Pick list (dual pane)](pick-list.md) | hand-built | (b) Datastar signals (array bind, filter) |
| [Progress](progress.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Radio group](radio-group.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Row actions](row-actions.md) | new (Go platform port) | (a) links + native popover menu; (b) positioning |
| [Search input](search-input.md) | hand-built | (a) native |
| [Select](select.md) | fc-web themed copy | (a) native |
| [Separator](separator.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Sheet](sheet.md) | fc-web themed copy | (b) Datastar attributes opening a native modal `<dialog>` |
| [Sidebar (Topcoat sidebar)](sidebar.md) | upstream Topcoat UI registry 0.9.0 | (b) Datastar signals (collapse, mobile sheet) |
| [Skeleton](skeleton.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Spinner](spinner.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Switch](switch.md) | fc-web themed copy | (a) native |
| [Table](table.md) | fc-web themed copy | (a) native |
| [Table toolbar](table-toolbar.md) | hand-built | (a) native form; (c) live, with the state part as patch target |
| [Tabs](tabs.md) | upstream Topcoat UI registry 0.9.0 | (b) Datastar signal (client tabs) or (a) plain links / (c) server-rendered panel |
| [Tag](tag.md) | hand-built | (a) native |
| [Textarea](textarea.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Toggle and toggle group](toggle.md) | upstream Topcoat UI registry 0.9.0 | (a) native |
| [Tooltip](tooltip.md) | fc-web themed copy | (a) CSS only |
