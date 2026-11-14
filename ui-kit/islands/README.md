# Islands

Scripts that the server-rendered UI loads beyond Datastar, for the components that need them
(`docs/contracts/server-rendered-ui.md` section 2 rule 5 and section 4). The built files live in
`framework-ui/src/main/resources/fw-ui/`, the single source for the Java module and (copied by
`go/internal/conformance/sync-shared.sh`) the Go module `go/ui`. Each file travels with its licence
notice there; the Go tests pin each file's sha256.

| File in `fw-ui/` | What it is | Source here | sha256 |
|---|---|---|---|
| `fc-code-editor.js` | CodeMirror 6 (JSON, YAML) as the `<fc-code-editor>` element | `codemirror/` (esbuild bundle) | `74b59f1db8170b490d4a76140dd2c8318d62581bb95362c1563da295f99ca49f` |
| `LICENSE-codemirror.md` | licences of every package bundled into `fc-code-editor.js` | written by the same build | `f0a81fc1e83230c619368ff2edf4e99c61452481c1e3729afa33e64588e75dea` |
| `fc-diagram.js` | the `<fc-diagram>` element, which loads `mermaid.min.js` on the first diagram | the file itself (hand-written, no build) | `49aecf56c42fe89a9d28d1b9f4d727be09643611568d7f4978a5cc0b03be94d8` |
| `mermaid.min.js` | Mermaid 12.3.0, `dist/mermaid.min.js` of the npm package, unmodified | none (vendored) | `336df803d87a38b70a01e736e666c8f7601d2ce42f5e47c8fafe5d721074b9a3` |

`fc-webauthn.js` (the FlowCatalyst platform's passkey glue) is not here: it is platform code and the
platform serves it as an extra file of its own `assets` instance.

## Rebuilding `fc-code-editor.js`

Only when upgrading a pinned version (edit the exact versions in `codemirror/package.json`). Needs
Node and npm; the build is reproducible for the pinned versions (rebuilt 2026-11-02 from the
lockfile, same sha256 as the table).

```sh
cd ui-kit/islands/codemirror
npm ci
npm run build          # writes fw-ui/fc-code-editor.js and fw-ui/LICENSE-codemirror.md
```

Then update the sha256 in this table, in `go/ui/assets` (`CodeEditorSHA256`) and in the Java
assets test, and run `make -C go go-sync-shared` so the Go copies follow.

## Replacing Mermaid

```sh
npm pack mermaid@<version>      # in an empty directory
tar -xzf mermaid-<version>.tgz package/dist/mermaid.min.js package/LICENSE
```

Copy `package/dist/mermaid.min.js` to `fw-ui/mermaid.min.js` and the licence text into
`fw-ui/LICENSE-mermaid.md`, record the new sha256 here and in `go/ui/assets` (`MermaidSHA256`),
then run `make -C go go-sync-shared`.

## Datastar

`datastar.js` is Datastar 1.0.5 from
`https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.5/bundles/datastar.js`, unmodified, sha256
`7380e0344b7f937d80ad70ededa1c28578173157ee4748be1850991491d911fd` (contract section 4 rule 2).
