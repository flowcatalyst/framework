# Alert dialog

**Source:** fc-web themed copy (`crates/fc-web/src/components/alert_dialog.rs`)  
**Mechanism:** (b) Datastar attributes opening a native modal `<dialog role="alertdialog">`  

## Purpose

A dialog that interrupts to ask for a decision (confirmations). Same structure as `dialog`, with `role="alertdialog"` and no light dismiss.

## Structure

```html
<dialog role="alertdialog" id="dlg-del" aria-labelledby="dlg-del-title" aria-describedby="dlg-del-desc" class="fixed inset-0 z-50 size-full max-h-none max-w-none items-start justify-center overflow-y-auto bg-background/80 p-4 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]">
  <div class="relative my-auto flex w-full max-w-lg flex-col gap-4 rounded-xl border border-border bg-card p-6 text-card-foreground shadow-sm scale-95 opacity-0 in-[[open]]:scale-100 in-[[open]]:opacity-100 starting:in-[[open]]:scale-95 starting:in-[[open]]:opacity-0 [transition:scale_200ms_ease-out,opacity_200ms_ease-out]">
    <div class="flex flex-col gap-1.5">
      <h2 id="dlg-del-title" class="text-lg leading-none font-semibold">Delete this workspace?</h2>
      <p id="dlg-del-desc" class="text-sm text-muted-foreground">Its projects and deploys go with it.</p>
    </div>
    
    <div class="flex flex-wrap items-center justify-end gap-2"><button class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 h-9 gap-2 rounded-lg px-4" type="button" autofocus data-on:click="el.closest('dialog').close()">Keep it</button><button class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent bg-destructive text-destructive-foreground shadow-xs hover:bg-destructive/90 active:bg-destructive/80 h-9 gap-2 rounded-lg px-4" type="submit">Delete</button></div>
  </div>
</dialog>
```

## Accessibility

`role="alertdialog"`, `aria-labelledby` (title) and `aria-describedby` (the message) are required. The safe action (Cancel) gets `autofocus`. A mask click must not dismiss it; Escape does (native). Everything else as `dialog.md`.

## Behaviour

As `dialog.md` without the light-dismiss attribute. Confirmation that submits: put the footer buttons inside a `<form method="post">` (a) or use `@post('/x/42/delete', {contentType: 'form'})` on the destructive button (c); the reply patches a flash and the changed region.

## Notes for a template author

Parameters: id, title, message, confirm label, confirm variant (destructive/primary), action URL, hidden fields. The hand-built `confirm-dialog.md` is the older equivalent that posts a form.
