# Marquee

A ClojureScript UI service built with **re-frame + Tailwind + shadcn/ui**,
compiled by **shadow-cljs**, and packaged with **Nix** as a flake.

Dependencies come from two sources, each with its own lockfile for
reproducible, offline Nix builds:

- `deps.edn` → `deps-lock.json` — Maven deps (shadow-cljs, reagent, re-frame),
  locked by [clj-nix](https://github.com/jlesquembre/clj-nix).
- `package.json` → `package-lock.json` — npm deps (react, Tailwind, and the
  shadcn helpers `class-variance-authority`, `clsx`, `tailwind-merge`).

## Usage

First-time setup — generate both lockfiles (required before `nix build`):

```sh
nix run .#update
```

Re-run it whenever you change `deps.edn` or `package.json`. Commit both
lockfiles.

Build the static site (shadow-cljs release + Tailwind):

```sh
nix build      # output in ./result
```

Serve the built site:

```sh
nix run        # http://localhost:8080  (override with PORT=3000 nix run)
```

### Local development (hot reload)

```sh
nix develop                       # shell with clojure, jdk, node, deps-lock
npm install                       # populate node_modules
npx shadow-cljs watch app         # cljs hot reload + dev server on :8080
# in another terminal, rebuild CSS on change:
npx tailwindcss -c tailwind.config.js -i src/css/main.css -o public/css/main.css --watch
```

## Layout

- `shadow-cljs.edn` — build config (`:browser` target, reads `deps.edn`).
- `src/marquee/core.cljs` — entry point; boots re-frame and mounts the app.
- `src/marquee/{events,subs,views}.cljs` — re-frame state + tiny page router.
- `src/marquee/pages/{home,about}.cljs` — the two example pages.
- `src/marquee/components/{button,card}.cljs` — shadcn/ui as Reagent components.
- `src/marquee/lib/utils.cljs` — the shadcn `cn` helper.
- `src/css/main.css` — Tailwind entry + shadcn design tokens.
- `tailwind.config.js`, `postcss.config.js` — Tailwind setup.
- `flake.nix` — `build` / `run` / `update` wiring.

## shadcn/ui approach

shadcn ships its components as React/TSX you copy into your project. Since
shadow-cljs compiles ClojureScript (not TSX), the components here are
reimplemented as small Reagent components that use shadcn's exact Tailwind
classes, design tokens, and the same npm helpers (`cva` for variants,
`clsx` + `tailwind-merge` for class merging). The result looks and behaves
like shadcn/ui while keeping the build pure-cljs.
