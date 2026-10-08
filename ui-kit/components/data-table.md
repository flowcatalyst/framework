# Data table / list page (composition)

**Source:** composition of `table`, `table-toolbar`, `paginator`, `empty-state`, `drawer`, `page-header`  
**Mechanism:** (b) client-side filter/sort/drawer; (c) server-side equivalents  

## Purpose

How a FlowCatalyst list page is assembled, with the client-only behaviours (quick filter, sort, row-opens-drawer) that need no server, and the server-driven equivalents.

## Structure

```html
<div data-signals="{usersSortCol: '', usersSortDir: 'asc', selUser: '', selEmail: '', qUsers: ''}"
     data-computed:q-users-norm="$qUsers.trim().toLowerCase()"
     data-computed:users-shown="[...document.querySelectorAll('#users-rows [data-filter-text]')].filter(r => r.dataset.filterText.includes($qUsersNorm)).length">
<header class="fc-page-header">
  <div>
    <h1 class="fc-page-title">Users</h1>
    <p class="fc-page-subtitle">Manage platform users and their access</p>
  </div>
  <div class="flex items-center gap-2"><button type="button" class="fc-btn fc-btn-primary"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-users"/></svg> New user</button></div>
</header>
<div class="fc-card-flush">
<form id="users-list" method="get" action="#" class="fc-table-toolbar" data-on:submit="void 0">
  <div class="fc-toolbar-start">
    <div class="fc-input-icon fc-toolbar-search"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-search"/></svg><input type="search" name="q" class="fc-input" placeholder="Search users" aria-label="Search users" data-bind:q-users></div>
  </div>
  <div class="fc-toolbar-end">
    
    <button type="submit" class="fc-icon-btn" title="Refresh" aria-label="Refresh"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-refresh-cw"/></svg></button>
    
    <button type="button" class="fc-btn fc-btn-primary-outline" popovertarget="users-list-filters"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-funnel"/></svg> Filters </button>
    <div id="users-list-filters" popover class="fc-filter-popover" data-on:toggle__capture="evt.newState === 'open' && (el.style.top = (el.previousElementSibling.getBoundingClientRect().bottom + 6) + 'px'); evt.newState === 'open' && (el.style.left = Math.max(16, el.previousElementSibling.getBoundingClientRect().right - el.offsetWidth) + 'px')">
      <div class="fc-filter-popover-body">
<div class="fc-form-field">
  <label class="fc-field-label" for="filter-scope">Type</label>
  <select id="filter-scope" name="scope" class="fc-select"><option value="">All types</option><option value="ANCHOR">Anchor</option><option value="PARTNER">Partner</option><option value="CLIENT">Client</option></select>
</div>
<div class="fc-form-field">
  <label class="fc-field-label" for="filter-active">Status</label>
  <select id="filter-active" name="active" class="fc-select"><option value="">All statuses</option><option value="true">Active</option><option value="false">Inactive</option></select>
</div>
      </div>
    </div>
  </div>
</form>
<div class="w-full overflow-x-auto"><table class="w-full caption-bottom border-collapse text-sm [&_td]:py-[0.4rem] [&_td]:px-3 [&_th]:px-3">
<thead class="[&_tr]:border-b bg-subtle [&_th]:h-9 [&_th]:text-[12px] [&_th]:font-semibold [&_th]:tracking-wider [&_th]:text-subtle-foreground [&_th]:uppercase"><tr class="border-b border-border transition-colors hover:bg-foreground/5 hover:bg-transparent">
<th class="h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground" data-attr:aria-sort="$usersSortCol === 'name' ? ($usersSortDir === 'asc' ? 'ascending' : 'descending') : false"><a href="#" class="inline-flex items-center gap-1 text-inherit no-underline hover:text-foreground" data-on:click__prevent="$usersSortDir = ($usersSortCol === 'name' && $usersSortDir === 'asc') ? 'desc' : 'asc'; $usersSortCol = 'name'; const b = document.getElementById('users-rows'); [...b.querySelectorAll('tr[data-filter-text]')].sort((x, y) => x.cells[0].textContent.localeCompare(y.cells[0].textContent) * ($usersSortDir === 'asc' ? 1 : -1)).forEach(r => b.append(r))">Name<span data-show="$usersSortCol !== 'name'"><svg width="1em" height="1em" class="size-[0.85rem] opacity-40" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-arrow-up-down"/></svg></span><span data-show="$usersSortCol === 'name' && $usersSortDir === 'asc'" style="display: none"><svg width="1em" height="1em" class="size-[0.85rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-arrow-up"/></svg></span><span data-show="$usersSortCol === 'name' && $usersSortDir === 'desc'" style="display: none"><svg width="1em" height="1em" class="size-[0.85rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-arrow-down"/></svg></span></a></th>
<th class="h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground" data-attr:aria-sort="$usersSortCol === 'email' ? ($usersSortDir === 'asc' ? 'ascending' : 'descending') : false"><a href="#" class="inline-flex items-center gap-1 text-inherit no-underline hover:text-foreground" data-on:click__prevent="$usersSortDir = ($usersSortCol === 'email' && $usersSortDir === 'asc') ? 'desc' : 'asc'; $usersSortCol = 'email'; const b = document.getElementById('users-rows'); [...b.querySelectorAll('tr[data-filter-text]')].sort((x, y) => x.cells[1].textContent.localeCompare(y.cells[1].textContent) * ($usersSortDir === 'asc' ? 1 : -1)).forEach(r => b.append(r))">Email<span data-show="$usersSortCol !== 'email'"><svg width="1em" height="1em" class="size-[0.85rem] opacity-40" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-arrow-up-down"/></svg></span><span data-show="$usersSortCol === 'email' && $usersSortDir === 'asc'" style="display: none"><svg width="1em" height="1em" class="size-[0.85rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-arrow-up"/></svg></span><span data-show="$usersSortCol === 'email' && $usersSortDir === 'desc'" style="display: none"><svg width="1em" height="1em" class="size-[0.85rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-arrow-down"/></svg></span></a></th>
<th class="h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground">Type</th><th class="h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground">Status</th></tr></thead>
<tbody id="users-rows" class="[&_tr:last-child]:border-0 [&_tr:nth-child(even)]:bg-subtle">
<tr data-show="$usersShown === 0" style="display: none"><td colspan="4"><div class="fc-empty" role="status">
  <svg width="1em" height="1em" class="size-12" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-inbox"/></svg>
  <span>No users found</span>
</div></td></tr>
<tr class="border-b border-border transition-colors hover:bg-foreground/5 cursor-pointer" data-filter-text="ada lovelace ada@example.com" data-show="el.dataset.filterText.includes($qUsersNorm)" data-on:click="$selUser = 'Ada Lovelace'; $selEmail = 'ada@example.com'"><td class="p-3 align-middle whitespace-nowrap"><a href="#" onclick="event.preventDefault()" class="font-medium text-foreground no-underline">Ada Lovelace</a></td><td class="p-3 align-middle whitespace-nowrap text-[13px] text-muted-foreground">ada@example.com</td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-info text-info-foreground">Anchor</span></td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-success text-success-foreground">Active</span></td></tr><tr class="border-b border-border transition-colors hover:bg-foreground/5 cursor-pointer" data-filter-text="grace hopper grace@example.com" data-show="el.dataset.filterText.includes($qUsersNorm)" data-on:click="$selUser = 'Grace Hopper'; $selEmail = 'grace@example.com'"><td class="p-3 align-middle whitespace-nowrap"><a href="#" onclick="event.preventDefault()" class="font-medium text-foreground no-underline">Grace Hopper</a></td><td class="p-3 align-middle whitespace-nowrap text-[13px] text-muted-foreground">grace@example.com</td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-warning text-warning-foreground">Partner</span></td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-success text-success-foreground">Active</span></td></tr><tr class="border-b border-border transition-colors hover:bg-foreground/5 cursor-pointer" data-filter-text="alan turing alan@example.com" data-show="el.dataset.filterText.includes($qUsersNorm)" data-on:click="$selUser = 'Alan Turing'; $selEmail = 'alan@example.com'"><td class="p-3 align-middle whitespace-nowrap"><a href="#" onclick="event.preventDefault()" class="font-medium text-foreground no-underline">Alan Turing</a></td><td class="p-3 align-middle whitespace-nowrap text-[13px] text-muted-foreground">alan@example.com</td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-foreground/5 text-foreground">Client</span></td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-foreground/5 text-foreground">Inactive</span></td></tr><tr class="border-b border-border transition-colors hover:bg-foreground/5 cursor-pointer" data-filter-text="edsger dijkstra edsger@example.com" data-show="el.dataset.filterText.includes($qUsersNorm)" data-on:click="$selUser = 'Edsger Dijkstra'; $selEmail = 'edsger@example.com'"><td class="p-3 align-middle whitespace-nowrap"><a href="#" onclick="event.preventDefault()" class="font-medium text-foreground no-underline">Edsger Dijkstra</a></td><td class="p-3 align-middle whitespace-nowrap text-[13px] text-muted-foreground">edsger@example.com</td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-foreground/5 text-foreground">Client</span></td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-success text-success-foreground">Active</span></td></tr><tr class="border-b border-border transition-colors hover:bg-foreground/5 cursor-pointer" data-filter-text="barbara liskov barbara@example.com" data-show="el.dataset.filterText.includes($qUsersNorm)" data-on:click="$selUser = 'Barbara Liskov'; $selEmail = 'barbara@example.com'"><td class="p-3 align-middle whitespace-nowrap"><a href="#" onclick="event.preventDefault()" class="font-medium text-foreground no-underline">Barbara Liskov</a></td><td class="p-3 align-middle whitespace-nowrap text-[13px] text-muted-foreground">barbara@example.com</td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-warning text-warning-foreground">Partner</span></td><td class="p-3 align-middle whitespace-nowrap"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-foreground/5 text-foreground">Inactive</span></td></tr>
</tbody></table></div>
<nav class="fc-paginator" aria-label="Pagination">
  <span class="fc-page-link" aria-disabled="true" aria-label="First"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevrons-left"/></svg></span>
  <span class="fc-page-link" aria-disabled="true" aria-label="Previous"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-left"/></svg></span>
  <span class="fc-page-link" aria-current="page">1</span>
  <span class="fc-page-link" aria-disabled="true" aria-label="Next"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></span>
  <span class="fc-page-link" aria-disabled="true" aria-label="Last"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevrons-right"/></svg></span>
  <select name="rows" form="users-list" class="fc-select ml-2" aria-label="Rows per page" data-on:change="el.form.requestSubmit()"><option value="50" selected>50</option><option value="100">100</option><option value="250">250</option><option value="500">500</option></select>
  <span class="fc-page-report">Showing 1 to 5 of 5 users</span>
</nav>
</div>
<aside class="fc-drawer" role="complementary" aria-label="User" data-drawer style="display: none"
       data-show="$selUser !== ''"
       data-on:keydown__window="evt.key === 'Escape' && $selUser !== '' && !document.querySelector('dialog[open]:not([data-drawer]), [popover]:popover-open') && ($selUser = '')">
  <button type="button" class="fc-icon-btn absolute top-[1.1rem] right-[1.1rem] z-10" aria-label="Close" data-on:click="$selUser = ''"><svg width="1em" height="1em" class="size-[1.2rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></button>
  <header class="fc-drawer-header pr-14">
    <div class="fc-drawer-titles">
      <div class="min-w-0">
        <h2 class="fc-drawer-title" data-text="$selUser"></h2>
        <p class="fc-drawer-subtitle" data-text="$selEmail"></p>
      </div>
      <div class="flex shrink-0 items-center gap-2"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-success text-success-foreground">Active</span></div>
    </div>
  </header>
  <div class="fc-drawer-body">
<section class="fc-form-section">
  <header class="fc-section-header">
    <div>
      <h3 class="fc-section-title">Details</h3>
    </div>
    <button type="button" class="fc-btn fc-btn-text-primary">Edit</button>
  </header>
  <div class="fc-section-body">
<div class="fc-detail-grid"><div class="fc-detail-field">
  <span class="fc-field-label">Name</span>
  <div class="fc-detail-value"><span data-text="$selUser"></span></div>
</div><div class="fc-detail-field">
  <span class="fc-field-label">Email</span>
  <div class="fc-detail-value"><span data-text="$selEmail"></span></div>
</div></div>
  </div>
</section>
  </div>
  <div class="fc-drawer-footer"><button type="button" class="fc-btn fc-btn-text" data-on:click="$selUser = ''">Close</button></div>
</aside>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Page signals | `<div data-signals="{usersSortCol: '', usersSortDir: 'asc', selUser: '', selEmail: '', qUsers: ''}">` |  | Root of the page region |
| Normalised query | `data-computed:q-users-norm="$qUsers.trim().toLowerCase()"` |  |  |
| Visible count | `data-computed:users-shown="[...document.querySelectorAll('#users-rows [data-filter-text]')].filter(r => r.dataset.filterText.includes($qUsersNorm)).length"` |  | Drives the empty state |
| Search box | `input[data-bind:q-users]` |  | Inside the toolbar form |
| Row | `<tr data-filter-text="{lowercase searchable text}" data-show="el.dataset.filterText.includes($qUsersNorm)" data-on:click="$selUser = ...">` |  |  |
| Empty row | `<tr data-show="$usersShown === 0" style="display: none">` |  | Contains `empty-state` |
| Sort heading | `<th aria-sort data-attr:aria-sort=...> <a href="#" data-on:click__prevent="...">` |  | see Behaviour |

## Accessibility

Sort headings are links (or buttons) inside `<th aria-sort>`; the active column's `aria-sort` is `ascending`/`descending`, others absent. Filtered-out rows are `display: none`. The empty row announces via `role="status"`.

## Behaviour

**Quick filter (b):** the search input is bound (`data-bind:q-users`); rows `data-show` against the lower-cased query; `$usersShown` drives the empty row. Replaces `ui.js` `data-filter-list` / `data-filter-text`.

**Sort (b, new in this kit; the hand-built list sorted on the server):** the heading handler sets direction/column signals then re-appends rows in order:
`$usersSortDir = ($usersSortCol === 'name' && $usersSortDir === 'asc') ? 'desc' : 'asc'; $usersSortCol = 'name'; const b = document.getElementById('users-rows'); [...b.querySelectorAll('tr[data-filter-text]')].sort((x, y) => x.cells[0].textContent.localeCompare(y.cells[0].textContent) * ($usersSortDir === 'asc' ? 1 : -1)).forEach(r => b.append(r))`
This is JavaScript in an attribute: acceptable for one table, not a pattern to multiply. It sorts only the rows in the DOM (one page).

**Server-side equivalents (c), used when the list is paged:**

| Interaction | Datastar attribute | Server reply (SSE) |
|---|---|---|
| search | `data-on:input__debounce.300ms="@get('/users')"` with `data-bind:q` | `datastar-patch-elements`, `selector #users-rows`, `mode inner`; also patch the paginator |
| sort | `data-on:click__prevent="@get('/users?sort=name&dir=desc')"` on the heading link (real `href` kept for no-JS) | patch `#users-rows` and the `<thead>` (to move `aria-sort`/icons) |
| page / rows | link or select as in `paginator.md` | patch `#users-rows` and the paginator |
| open drawer | `data-on:click="$selUser = 'u-42'; @get('/users/u-42/drawer')"` | patch `#user-drawer-body` |

Without Datastar the same links and GET form work as ordinary navigation (a).

## Notes for a template author

Parameters: columns, rows, sort state, page, filters. Slots: row cells, toolbar filters, drawer body. All signal names are suffixed per list (`users…`).
