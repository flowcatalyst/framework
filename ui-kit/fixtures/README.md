# Conformance fixtures

Language-neutral test cases for the component specs. Every template implementation of the kit (templ in the Go platform's `internal/ui/kit`, JTE in the framework) renders each fixture and compares the result with the golden HTML. A component or variant is not done until its fixture exists and every implementation passes it.

## Layout

```
fixtures/<component>/<case>.json   props + slots
fixtures/<component>/<case>.html   golden markup
```

`<component>` is the kit's name for the component (the spec file name, or a part of it: `table-row`, `sortable-header`, `user-menu`, ...). The spec's **Props** table says which fixture directories belong to it.

## The JSON

```json
{
  "props": { "variant": "outline", "size": "sm" },
  "slots": { "default": "sm" },
  "source": "gallery/index.html: element starting with '<button class=...' (occurrence 11)",
  "notes": "optional"
}
```

| Key | Meaning |
|---|---|
| `props` | The component's parameters, named as in the spec's Props table (camelCase). Absent props take their default. Implementations should decode strictly: an unknown name is an error, so a typo cannot silently test the defaults. |
| `slots` | Caller markup per slot, as raw HTML (`default` is the unnamed slot; named slots as in the Props table). Inserted verbatim. A slot the component does not have is an error. |
| `source` | Where the golden came from (informational). |
| `notes` | Optional. |

Prop values are JSON: strings, booleans, numbers, lists and objects (`options: [{"value": "a", "label": "Anchor"}]`). `attrs` props are objects of attribute name to value; `true` means a bare attribute (`{"data-bind:q": true}` renders `data-bind:q`).

## The golden

The golden is the component's markup as the **gallery** renders it (the browser-checked reference), copied verbatim: the element whose start tag the `source` names (or its children, when the gallery wraps the component in a layout element), with one rewrite: the sprite path `../icons/lucide-sprite.svg` becomes `/icons/lucide-sprite.svg`, the spec's form. Implementations render icons with that default sprite URL in the tests.

When the gallery has no example of a case (a disabled textarea, a pager on its last page, a drawer with server-loaded content), the golden is written from the spec's Structure and option tables, and `source` says so. Proposed markup that is not yet in a spec (`row-actions`) is marked PROPOSED in `source` and in the spec.

## Comparison (normalisation)

Both sides are normalised, then compared as text:

1. Parse with the HTML5 fragment algorithm, in the context of the element that would contain the fragment's first element: `tbody` for `<tr>`, `tr` for `<td>`/`<th>`, `table` for `<thead>`/`<tbody>`/`<tfoot>`/`<caption>`/`<colgroup>`, `select` for `<option>`/`<optgroup>`, otherwise `body`. (So entities are decoded, and quoting, attribute case and `<use/>` vs `<use></use>` stop mattering.)
2. Drop comments.
3. Sort each element's attributes by name. Collapse whitespace in every attribute value (runs become one space) and trim it. **Class token order is kept**: the specs define it (BASE + variant + size).
4. Collapse whitespace in text and trim it; drop whitespace-only text. Consequence: leading and trailing spaces inside a text node are not compared (`<svg/> Older` equals `<svg/>Older`).
5. Serialise one node per line, indented by depth (the Go implementation is `internal/ui/htmlnorm`; it prints a line diff on failure).

Boolean attributes compare as empty values (`disabled` equals `disabled=""`), so `disabled="disabled"` would differ: render bare boolean attributes.

## Adding a fixture

1. Find the variant in `gallery/` (add it there first if it is missing and the spec allows it), copy the element into `<case>.html`, and rewrite the sprite path.
2. Write `<case>.json` with the props that produce it and the slots it needs; set `source`.
3. Run each implementation's conformance test. A mismatch means the implementation, the spec or the gallery is wrong: fix the one that is wrong, and if it is the spec or the gallery, fix both together (they must not disagree).
4. The Go platform vendors this directory (`scripts/ui-kit-sync.sh` copies it to `internal/ui/testdata/fixtures`); its test can also read this directory directly with `UI_KIT_FIXTURES=/path/to/ui-kit/fixtures`.

## Cases that cannot be a pure function of props

None at present. The gallery's client-side list page (quick filter, client sort and the drawer bound by `data-text`) is page composition, not one component: its fixtures cover the parts (`table-row` with the filter attributes as `attrs`, `drawer` with `titleSignal`), not the whole list page. Interaction (dialogs opening, popover anchoring) is checked in a browser, not here (`gallery/CHECKS.md`).
