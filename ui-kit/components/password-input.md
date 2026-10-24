# Password input

**Source:** new (Go platform port; replaces PrimeVue `Password` with its strength meter)  
**Mechanism:** (b) Datastar signals: show/hide toggle and an advisory strength meter  

## Purpose

A password field with a show/hide toggle and, optionally, a strength meter. The meter is **advice only**: the server's password policy (length, common-password list, identity checks) is the authority and its error is shown as for any field. The meter never blocks submit.

## Structure

```html
<div class="fc-password" data-signals="{newPwShow: false, newPwScore: 0}"><div class="relative"><input type="password" class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 pr-10" id="new-pw" name="password" autocomplete="new-password" placeholder="At least 12 characters" required aria-describedby="new-pw-strength" data-attr:type="$newPwShow ? &#39;text&#39; : &#39;password&#39;" data-on:input="const v = el.value; $newPwScore = !v ? 0 : v.length &lt; 8 ? 1 : Math.min(4, 1 + (v.length &gt;= 12) + (v.length &gt;= 16) + (/[a-z]/.test(v) &amp;&amp; /[A-Z]/.test(v)) + /\d/.test(v) + /[^A-Za-z0-9]/.test(v))"> <button type="button" class="fc-password-toggle" aria-label="Show password" aria-controls="new-pw" data-attr:aria-label="$newPwShow ? &#39;Hide password&#39; : &#39;Show password&#39;" data-on:click="$newPwShow = !$newPwShow"><span data-show="!$newPwShow"><svg width="1em" height="1em" class="size-4" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-eye"></use></svg></span> <span data-show="$newPwShow" style="display: none"><svg width="1em" height="1em" class="size-4" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-eye-off"></use></svg></span></button></div><div class="fc-password-meter" aria-hidden="true" data-score="0" data-attr:data-score="String($newPwScore)"><span></span><span></span><span></span><span></span></div><p id="new-pw-strength" class="fc-password-strength" aria-live="polite" data-text="[&#39;&#39;, &#39;Weak&#39;, &#39;Fair&#39;, &#39;Good&#39;, &#39;Strong&#39;][$newPwScore]"></p><p class="fc-password-help">The meter is advice only; the password policy is checked when you save.</p></div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div class="fc-password">` | `data-signals="{ {signal}Show: false, {signal}Score: 0 }"` | |
| Input | `<input type="password">` | the `input.md` classes + `pr-10`; `data-attr:type="${signal}Show ? 'text' : 'password'"` | `autocomplete="new-password"` (or `current-password` for sign-in) |
| Toggle | `<button type="button" class="fc-password-toggle">` | `aria-controls="{id}"`; `data-attr:aria-label` ("Show password" / "Hide password"); icons `eye` / `eye-off` swapped with `data-show` | |
| Meter | `<div class="fc-password-meter" aria-hidden="true">` | four `<span>` segments; `data-attr:data-score="String(${signal}Score)"` colours the first N | only with `meter` |
| Strength text | `<p id="{id}-strength" aria-live="polite">` | `data-text` = Weak / Fair / Good / Strong (empty at score 0) | the accessible form of the meter; in the input's `aria-describedby` |
| Help | `<p class="fc-password-help">` | | says the meter is advice only |

## Props

Fixtures, from the ui-kit root: `fixtures/password-input`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required |
| `name` | string | - | |
| `signal` | string | - | required; camelCase base of `{signal}Show` and `{signal}Score`, unique per page |
| `placeholder` | string | - | |
| `required`, `disabled`, `invalid` | boolean | false | `invalid` sets `aria-invalid` |
| `describedBy` | string | - | id of the server's error text; listed after the strength line |
| `autocomplete` | string | `new-password` | `current-password` on a sign-in form |
| `meter` | boolean | false | adds meter, strength text and help |
| `class` | string | - | on the root |

## Accessibility

- The toggle is a real button with a changing accessible name; the input keeps focus and value when toggled.
- The meter is `aria-hidden`; the strength word is announced politely and referenced by `aria-describedby`.
- Without Datastar the field is a plain password input (the toggle and meter do nothing).

## Behaviour

**(b)** The score on each `input`: empty is 0; under 8 characters is 1; from 8 characters it is 1, plus one for 12+ characters, one for 16+, one for mixed case, one for a digit and one for a symbol, capped at 4. This is deliberately simple and never claims a password is acceptable.

The signals hold only the toggle and the score (never the password), so they are safe to send with a request. Submit the password through a native form field; do not bind it to a signal.

## Notes for a template author

- Put the server's rejection (`passwordpolicy` message) in an error element and pass its id as `describedBy`.
- Do not use the meter for a sign-in field.
