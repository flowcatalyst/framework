# Secret reference input

**Source:** new (Go platform port; the SPA's secret-reference fields)  
**Mechanism:** (a) native `pattern` validation  

## Purpose

A text field for the **name** of a secret held in a secret store (a connection's signing secret, an IdP client secret), never the secret value. The help text says so, and the pattern rejects values that cannot be a name.

## Structure

```html
<div class="flex flex-col gap-2"><input type="text" class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 font-mono" id="conn-secret" name="secretRef" placeholder="my-secret-name" pattern="([a-z0-9]+(-[a-z0-9]+)*:)?[a-z0-9]+(-[a-z0-9]+)*" title="Lowercase letters, digits and single hyphens, with an optional store: prefix" autocomplete="off" autocapitalize="off" spellcheck="false" required aria-describedby="conn-secret-help"><p id="conn-secret-help" class="text-sm leading-relaxed text-muted-foreground [&amp;_a]:underline [&amp;_a]:underline-offset-4">The name of a secret held in the secret store, such as <code>billing-api-key</code> or <code>vault:billing-api-key</code>. This is a reference, not the secret value: never type or paste the value here.</p></div>
```

## Props

Fixtures, from the ui-kit root: `fixtures/secret-ref-input`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required |
| `name` | string | - | |
| `value` | string | - | |
| `placeholder` | string | `my-secret-name` | |
| `required`, `disabled`, `invalid` | boolean | false | |
| `describedBy` | string | - | id of the error text; listed after the help |
| `class` | string | - | on the root |

## Validation

`pattern="([a-z0-9]+(-[a-z0-9]+)*:)?[a-z0-9]+(-[a-z0-9]+)*"`: a name of lowercase letters and digits in hyphen-separated groups, with an optional `store:` prefix (a name of the same shape) selecting the secret store. The server validates again; the browser pattern only catches typos early. A secret *value* (spaces, upper case, symbols) fails the pattern.

## Accessibility

The help text is the input's `aria-describedby` (then the error, if any). `autocomplete="off"`, `autocapitalize="off"` and `spellcheck="false"` keep browsers from offering or altering the value; `title` is the browser's pattern hint.

## Notes for a template author

Never render a stored secret value into this field: the value is a name. Show the reference as it was saved.
