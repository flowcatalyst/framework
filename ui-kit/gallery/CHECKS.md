# Gallery interaction checks

Scripted in system Chrome (headless, Playwright) against `gallery/` served over http, Datastar 1.0.4. Result of the last run: **69 passed, 0 failed**; the browser console had no errors or warnings.

| Result | Check |
|---|---|
| PASS | dialog (frosted): opens as modal with focus inside |
| PASS | dialog (frosted): Tab keeps focus inside (trap) |
| PASS | dialog (frosted): Escape closes and returns focus to trigger |
| PASS | dialog (frosted): mask click closes |
| PASS | dialog (frosted): close/cancel button closes |
| PASS | dialog (dark mask, X): opens as modal with focus inside |
| PASS | dialog (dark mask, X): Tab keeps focus inside (trap) |
| PASS | dialog (dark mask, X): Escape closes and returns focus to trigger |
| PASS | dialog (dark mask, X): mask click closes |
| PASS | dialog (dark mask, X): close/cancel button closes |
| PASS | alert dialog: opens as modal with focus inside |
| PASS | alert dialog: Tab keeps focus inside (trap) |
| PASS | alert dialog: Escape closes and returns focus to trigger |
| PASS | alert dialog: mask click does NOT close |
| PASS | alert dialog: close/cancel button closes |
| PASS | confirm dialog (hand-built): opens as modal with focus inside |
| PASS | confirm dialog (hand-built): Tab keeps focus inside (trap) |
| PASS | confirm dialog (hand-built): Escape closes and returns focus to trigger |
| PASS | confirm dialog (hand-built): mask click closes |
| PASS | confirm dialog (hand-built): close/cancel button closes |
| PASS | alert dialog: role=alertdialog and safe default focus (Keep it) |
| PASS | dialog X button closes |
| PASS | confirm dialog: submit closes |
| PASS | sheet left: opens modal, panel on the left, Escape closes |
| PASS | sheet right: opens modal, panel on the right, Escape closes |
| PASS | sheet top: opens modal, panel on the top, Escape closes |
| PASS | sheet bottom: opens modal, panel on the bottom, Escape closes |
| PASS | dropdown: summary click opens |
| PASS | dropdown: outside click closes (Datastar) |
| PASS | dropdown: Escape closes and focuses summary |
| PASS | dropdown: submenu opens; item click closes menu |
| PASS | dropdown: opening another menu leaves independent state |
| PASS | tooltip: shows on hover and on keyboard focus |
| PASS | hover card: shows on hover after delay |
| PASS | popover (native): opens, Escape closes |
| PASS | tabs: click switches panel and aria-current |
| PASS | tabs: URL hash unchanged on click (default prevented) |
| PASS | accordion: exclusive via name |
| PASS | checkbox toggles natively |
| PASS | switch toggles by label click |
| PASS | radio group: selecting one deselects other |
| PASS | dirty form: Save disabled + Discard hidden at rest |
| PASS | dirty form: typing enables Save and shows Discard |
| PASS | dirty form: change back disables Save again |
| PASS | dirty form: select change + Discard resets |
| PASS | list: filter box hides non-matching rows |
| PASS | list: no match shows empty state |
| PASS | list: sort by name toggles asc/desc and aria-sort |
| PASS | list: row click opens non-modal drawer with the row data |
| PASS | list: drawer stays interactive list (click another row switches) |
| PASS | list: Escape closes drawer |
| PASS | list: drawer X button closes |
| PASS | toolbar filters popover: opens, positioned under button, Escape closes |
| PASS | pick list: initial selection mirrored (count 1, Operator checked) |
| PASS | pick list: ticking adds to right pane, count 2, Save enabled |
| PASS | pick list: x on right pane unticks |
| PASS | pick list: filter hides non-matching items |
| PASS | local time: rewritten to viewer locale |
| PASS | theme switch toggles .dark on <html> and iframes |
| PASS | sidebar: starts expanded (16rem) |
| PASS | sidebar: trigger collapses to icon strip and expands again |
| PASS | sidebar: rail button collapses |
| PASS | sidebar: labels hidden (sr-only) when collapsed; active item has aria-current |
| PASS | app shell: collapse button toggles fc-collapsed + cookie |
| PASS | app shell: user menu popover opens; Escape closes |
| PASS | app shell: nav group <details> toggles |
| PASS | sidebar mobile: panel hidden, trigger opens sheet, Escape closes |
| PASS | sidebar mobile: close button and backdrop close |
| PASS | browser console: no errors/warnings |

Not covered: Firefox, Safari, screen readers, touch, a real backend (the Datastar action patterns were separately verified against a throwaway mock SSE server).
