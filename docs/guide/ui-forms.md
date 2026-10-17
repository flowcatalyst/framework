<!-- types: Spa -->
# Forms in the shared UI package

Notes for forms built on `@flowcatalyst/ui` and a generated API client (`@flowcatalyst/api-runtime`).
The package is TypeScript and Vue source (see [Static assets and the SPA](static-assets-and-spa.md)
for how a built application is served); the only Java type involved is `Spa`, which serves the built application.

## Optional fields: `optionalText` and `optionalNumber`

The framework's TypeScript is strict, including `exactOptionalPropertyTypes`, and the generated
client writes an optional field as `include?: string`: the property may be **absent**, never
present-and-`undefined`. Nuxt UI's `UInput`, `UTextarea` and `UInputNumber` cannot be bound to such a
property: they take a string (or a number or `null`), not `undefined`, so `v-model="state.include"`
does not compile.

`optionalText` and `optionalNumber` are the adapter. Each returns a writable ref that shows a missing
value as an empty field and, when the field is emptied, **removes the property** from the form state
instead of storing `""` or `undefined`. The state therefore always matches the generated type and
serialises without the key.

```vue
<script setup lang="ts">
import { optionalNumber, optionalText } from "@flowcatalyst/ui";
import { reactive } from "vue";
import type { SearchRequest } from "./api/orders"; // { name: string; include?: string; limit?: number }

const state = reactive<SearchRequest>({ name: "" });
const include = optionalText(state, "include", { trim: true });
const limit = optionalNumber(state, "limit");
</script>

<template>
  <UFormField label="Include" name="include"><UInput v-model="include" /></UFormField>
  <!-- a UTextarea takes the same ref: <UTextarea v-model="include" /> -->
  <UFormField label="Limit" name="limit"><UInputNumber v-model="limit" /></UFormField>
</template>
```

| Helper | Binds to | Missing reads as | Property is removed when |
|---|---|---|---|
| `optionalText(state, key, { trim? })` | `UInput`, `UTextarea` | `""` | the value is `""` (with `trim: true`, blank) |
| `optionalNumber(state, key)` | `UInputNumber` | `null` | the value is `null` or `NaN` |

- Pass the reactive object (`reactive(...)`, or a ref's `.value`), not a copy; the key must name an
  optional property of the matching type (a string key for `optionalText`, a number key for
  `optionalNumber`; anything else is a compile error, tested).
- `0` is a value, not empty: `optionalNumber` keeps it.
- Required fields do not need this: bind them directly.
- Source: `ui/packages/ui/src/composables/useOptionalFields.ts`, tests in
  `ui/packages/ui/test/optional-fields.test.ts` (including the type tests). Both are exported from
  the package index. Design notes and the report that asked for them: [U3, Camel feedback i](../ui/u3.md).
