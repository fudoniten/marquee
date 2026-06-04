# Marquee

A ClojureScript UI service built with **re-frame + Tailwind + shadcn/ui**,
compiled by **shadow-cljs**, and packaged with **Nix** as a flake.

This service provides a web interface for managing media from **Pseudovision**
and **Tunarr Scheduler**, with future support for **Tunabrain**.

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
# in a third terminal, run the backend BFF server:
clojure -M:server                 # backend proxy on :3000
```

### Environment Configuration

The backend proxy (BFF) requires environment variables to connect to the services:

```sh
# Pseudovision
export PSEUDOVISION_URL=https://pseudovision.kube.sea.fudo.link
export PSEUDOVISION_TOKEN=your-token-here  # optional

# Tunarr Scheduler
export TUNARR_SCHEDULER_URL=https://tunarr-scheduler.kube.sea.fudo.link
export TUNARR_SCHEDULER_TOKEN=your-token-here  # optional

# Tunabrain (future)
export TUNABRAIN_URL=https://tunabrain.kube.sea.fudo.link
export TUNABRAIN_TOKEN=your-token-here  # optional
```

You can also create a `.envrc` file (for use with [direnv](https://direnv.net/))
or a shell script to set these variables.

## Layout

### Frontend (ClojureScript)
- `shadow-cljs.edn` — build config (`:browser` target, reads `deps.edn`).
- `src/marquee/core.cljs` — entry point; boots re-frame and mounts the app.
- `src/marquee/{events,subs,views}.cljs` — re-frame state + tiny page router.
- `src/marquee/api.cljs` — martian-re-frame service bootstrap for OpenAPI integration.
- `src/marquee/pages/` — page components:
  - `home.cljs`, `about.cljs` — example pages.
  - `media.cljs` — displays media from Pseudovision libraries.
  - `media_detail.cljs` — shows metadata from both Pseudovision and Tunarr Scheduler.
- `src/marquee/components/{button,card}.cljs` — shadcn/ui as Reagent components.
- `src/marquee/lib/utils.cljs` — the shadcn `cn` helper.
- `src/css/main.css` — Tailwind entry + shadcn design tokens.
- `tailwind.config.js`, `postcss.config.js` — Tailwind setup.

### Backend (Clojure BFF)
- `src/marquee/server/core.clj` — Backend-For-Frontend proxy server.
- `src/marquee/server/config.clj` — Service configuration (URLs, tokens).

### Build
- `flake.nix` — `build` / `run` / `update` wiring.

## Service Integration Architecture

Marquee uses a **Backend-For-Frontend (BFF)** pattern to integrate with OpenAPI services:

1. **Frontend** (ClojureScript) uses [martian-re-frame](https://github.com/oliyh/martian)
   to auto-generate typed API clients from OpenAPI specs.
2. **BFF** (Clojure) proxies all API requests, adding authentication tokens and
   rewriting OpenAPI specs to route through itself.
3. **Services** (Pseudovision, Tunarr Scheduler, Tunabrain) expose OpenAPI REST APIs.

This architecture keeps credentials server-side and provides a clean, type-safe
API layer in the frontend.

### Making API Calls

From the frontend, dispatch martian requests:

```clojure
(rf/dispatch [::martian/request
              :pseudovision              ;; service ID
              :get-api-media-libraries   ;; operation ID from OpenAPI
              {}                         ;; params (path/query/body)
              [::on-success]             ;; success event
              [::on-failure]])           ;; failure event
```

The operation IDs are derived from the OpenAPI spec and follow the pattern
`verb-path-with-dashes` (e.g., `get-api-media-items-id` for `GET /api/media/items/{id}`).

## shadcn/ui approach

shadcn ships its components as React/TSX you copy into your project. Since
shadow-cljs compiles ClojureScript (not TSX), the components here are
reimplemented as small Reagent components that use shadcn's exact Tailwind
classes, design tokens, and the same npm helpers (`cva` for variants,
`clsx` + `tailwind-merge` for class merging). The result looks and behaves
like shadcn/ui while keeping the build pure-cljs.
