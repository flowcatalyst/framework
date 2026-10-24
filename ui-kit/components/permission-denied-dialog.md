# Permission-denied dialog

**Source:** port of the SPA's `PermissionDeniedModal.vue`  
**Mechanism:** (a) native modal `<dialog>`; (c) the server patches it in, `data-init` opens it  

## Purpose

What the user sees after a 403 or an expired session: a modal that says what happened. Two cases, chosen from the server's message the way the SPA does (a message mentioning "session", "expired" or "authenticated" is the session case):

| Case | Title | Behaviour |
|---|---|---|
| denied | Permission Denied | lock icon; message; "Required permission: `code`" when known; "Contact your administrator..." line; buttons **Go Back** (`history.back()`) and **Go to Dashboard** (link); closes on Escape, mask click |
| session | Session Expired | clock icon; message; one button **Log In Again** (link); `closedby="none"` and the cancel event is prevented, so it cannot be dismissed |

## Structure

Denied:

```html
<dialog id="perm-denied" class="fc-dialog w-[26rem] max-w-[calc(100vw-2rem)]" role="alertdialog" aria-labelledby="perm-denied-title" aria-describedby="perm-denied-desc" closedby="any" data-on:click="evt.target === el &amp;&amp; el.close()"><div class="fc-dialog-header"><span id="perm-denied-title">Permission Denied</span></div><div class="fc-dialog-body flex flex-col items-center gap-3 text-center"><span class="flex size-16 items-center justify-center rounded-full bg-red-500/15 text-red-600"><svg width="1em" height="1em" class="size-7" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-lock"></use></svg></span><p id="perm-denied-desc" class="text-[15px] leading-normal">You do not have permission to view dispatch pools.</p><p class="text-[13px] text-muted-foreground">Required permission: <code class="rounded bg-foreground/10 px-1.5 py-0.5 text-xs">platform:messaging:dispatch-pool:view</code></p><p class="text-[13px] text-muted-foreground">Contact your administrator if you believe you should have access to this resource.</p></div><div class="fc-dialog-footer"><button type="button" class="fc-btn fc-btn-text" autofocus data-on:click="history.back()">Go Back</button> <a class="fc-btn fc-btn-primary" href="/ui">Go to Dashboard</a></div></dialog>
```

Session:

```html
<dialog id="perm-session" class="fc-dialog w-[26rem] max-w-[calc(100vw-2rem)]" role="alertdialog" aria-labelledby="perm-session-title" aria-describedby="perm-session-desc" closedby="none" data-on:cancel="evt.preventDefault()"><div class="fc-dialog-header"><span id="perm-session-title">Session Expired</span></div><div class="fc-dialog-body flex flex-col items-center gap-3 text-center"><span class="flex size-16 items-center justify-center rounded-full bg-yellow-500/15 text-yellow-600"><svg width="1em" height="1em" class="size-7" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-clock"></use></svg></span><p id="perm-session-desc" class="text-[15px] leading-normal">Your session has expired. Please log in again.</p></div><div class="fc-dialog-footer"><a class="fc-btn fc-btn-primary" autofocus href="/ui/login?redirect=%2Fui%2Fpools">Log In Again</a></div></dialog>
```

## Props

Fixtures, from the ui-kit root: `fixtures/permission-denied-dialog`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required |
| `kind` | `denied` \| `session` | `denied` | |
| `message` | string | - | the server's text |
| `requiredPermission` | string | - | denied case only |
| `homeUrl` | string | `/ui` | |
| `loginUrl` | string | `/ui/login` | |
| `open` | boolean | false | adds `data-init="el.showModal()"`: the dialog opens when the markup arrives (a Datastar patch) |

## Accessibility

`role="alertdialog"` named by the title and described by the message; the primary action has `autofocus`. The session case cannot be escaped, as in the SPA.

## Notes for a template author

The failure path of a Datastar action (`_conventions.md`, Server contract) patches this into a page-level container with `open`; a full-page 403 renders it open over the page. `PermissionDeniedKindOf(message)` is the SPA's text rule.
