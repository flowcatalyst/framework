# Draft: upstream issue for vaul-vue (not posted)

Draft only (owner ruling 2026-10-14: draft, do not post anywhere). For
[github.com/unovue/vaul-vue](https://github.com/unovue/vaul-vue/issues). Facts checked on 2026-10-14
against the npm registry (`npm view vaul-vue`) and the installed 0.4.1 package; the repository's
`LICENSE` is the one copied into `ui/scripts/licences/vaul-vue.LICENSE`.

---

**Title:** npm package has no `license` field and no LICENSE file

**Body:**

The published `vaul-vue` package (0.4.1, the latest on npm) declares no licence:

- `package.json` has no `"license"` field, so `npm view vaul-vue license` prints nothing and
  licence tools (`pnpm licenses list`, `license-checker`, ...) report it as "Unknown";
- the tarball contains no licence file (its `"files"` list is `["README.md", "dist"]`), although the
  repository has an MIT `LICENSE`.

vaul-vue reaches many applications through Nuxt UI (`UDrawer`, which `UDashboardSidebar` and
`USidebar` import), so it ends up in their production bundles. Without a licence in the package,
every consumer that checks licences has to review it by hand and copy the licence text from the
repository to meet MIT's notice condition.

Would you add, in the next release:

1. `"license": "MIT"` to `package.json`;
2. the `LICENSE` file in the published package (npm always packs a `LICENSE` that sits next to the
   published `package.json`; if the package is published from a subdirectory, a copy there is enough);
3. optionally, the `"repository"` field pointing at `unovue/vaul-vue` (it still names
   `Elliot-Alexander/vaul-vue`).

Happy to open a pull request with these three changes if that helps. Thank you for maintaining the
port.
