# Marquee

A bare-bones ClojureScript UI service, built with Nix as a flake.

ClojureScript dependencies are fetched from Maven (via `deps.edn`) and pinned
for reproducible, offline Nix builds with a `deps-lock.json` lockfile generated
by [clj-nix](https://github.com/jlesquembre/clj-nix).

## Usage

First-time setup — generate the lockfile (required before `nix build`):

```sh
nix run .#update
```

This reads `deps.edn` and (re)writes `deps-lock.json`. Re-run it whenever you
change dependencies. Commit `deps-lock.json` alongside `deps.edn`.

Build the static site (compiles ClojureScript to `js/main.js`):

```sh
nix build
# output in ./result
```

Run it (serves the built site over HTTP):

```sh
nix run
# Serving marquee on http://localhost:8080
# override the port with: PORT=3000 nix run
```

## Layout

- `deps.edn` — Maven dependencies and source paths.
- `deps-lock.json` — generated lockfile (do not edit by hand).
- `src/marquee/core.cljs` — the ClojureScript entry point.
- `public/index.html` — page shell that loads the compiled `js/main.js`.
- `flake.nix` — `build` / `run` / `update` wiring.
