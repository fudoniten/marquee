# Marquee

Marquee is the web UI for the Pseudovision ecosystem. It's a ClojureScript
single-page app (re-frame, Tailwind, shadcn/ui-style components, built with
shadow-cljs) plus a small Clojure **BFF** (backend-for-frontend) that proxies
the SPA's requests to **Pseudovision**, **Tunarr Scheduler**, **Grout**, and
**Tunabrain**, and holds their credentials server-side.

Deployed at `https://marquee.kube.sea.fudo.link`.

Ongoing work lives in [PLAN.md](PLAN.md). Conventions and pitfalls for
contributors are in [AGENTS.md](AGENTS.md).

## Pages

| Page | What it shows |
|---|---|
| Home | Now Playing, active jobs, quick links |
| Media / Media detail | PV libraries and items; per-item metadata from PV and TS |
| Browse | Faceted browsing of the TS catalog |
| Collections | Your own lists of media items, kept in the browser's localStorage |
| Grout / Grout item | Grout filler and bumper clips, directory profiles |
| Guide / Schedule | Channel guide grid and per-channel playout schedule |
| Jobs | Async jobs from PV and TS |
| API Docs | Browsable OpenAPI docs for every configured service |

## Running locally

Everything runs from the Nix dev shell:

```sh
nix develop                      # clojure, jdk, node, deps-lock
source setup-env.sh              # or: cp .envrc.example .envrc && direnv allow
nix run .#dev-server             # shadow-cljs watch + Tailwind watch + BFF
```

The SPA is at `http://localhost:8080` (hot reload) and the BFF at
`http://localhost:3000`. shadow-cljs proxies `/api/*` and SPA routes to the BFF.

To run the pieces by hand instead (three terminals):

```sh
npx shadow-cljs watch app
npx tailwindcss -c tailwind.config.js -i src/css/main.css -o public/css/main.css --watch
PORT=3000 clojure -M:server      # the BFF defaults to :8080, which shadow-cljs uses
```

### Configuration

The BFF reads these environment variables. A service with no URL is simply
disabled; Marquee still starts.

| Variable | Purpose |
|---|---|
| `PSEUDOVISION_URL`, `PSEUDOVISION_TOKEN` | Pseudovision |
| `TUNARR_SCHEDULER_URL`, `TUNARR_SCHEDULER_TOKEN` | Tunarr Scheduler |
| `GROUT_URL`, `GROUT_TOKEN` | Grout |
| `TUNABRAIN_URL`, `TUNABRAIN_TOKEN` | Tunabrain (API Docs page only; the UI reaches Tunabrain through TS) |
| `JELLYFIN_URL`, `JELLYFIN_TOKEN` | Optional; thumbnails and direct links |
| `ENABLE_UI_DEBUG_TRACES` | Include stack traces in BFF 500 responses (default `true`) |
| `PORT` | BFF listen port (default `8080`; use `3000` in dev, where shadow-cljs has `8080`) |

Tokens are optional; no downstream service checks them yet.

## Building and deploying

```sh
nix run .#update                 # regenerate deps-lock.json + package-lock.json
nix build                        # static site only → ./result
nix build .#bff                  # BFF uberjar with the SPA embedded
nix run .#deployContainer        # build + push the image to registry.kube.sea.fudo.link
```

Run `nix run .#update` whenever `deps.edn` or `package.json` changes, and
commit both lockfiles: the Nix build is offline and fails on a stale lock.

## Testing

There is no automated test suite yet. Check changes by running the dev server
against the deployed services and exercising the affected pages.

## How it talks to the services

The SPA never calls a service directly. On first use the BFF fetches each
service's `/openapi.json`, rewrites its server URL to `/api/<service>`, and adds
an `operationId` (`{method}-{path-with-dashes}`) to any operation missing one.
The SPA loads those rewritten specs through
[martian-re-frame](https://github.com/oliyh/martian) and dispatches requests by
operation id:

```clojure
(rf/dispatch [::martian/request
              :get-api-media-items-id                   ;; GET /api/media/items/{id}
              {::martian/instance-id :pseudovision
               :id 123}                                 ;; path/query/body params
              [::on-success]
              [::on-failure]])
```

## Layout

```
src/marquee/
├── core.cljs              entry point; boots re-frame and mounts the app
├── events.cljs, subs.cljs re-frame events (all HTTP dispatch) and subscriptions
├── views.cljs, routes.cljs page table, nav, and URL routing
├── api.cljs               martian instances, one per service
├── pages/                 one namespace per page
├── components/            shadcn/ui-style Reagent components
├── lib/utils.cljs         the shadcn `cn` helper
└── server/
    ├── core.clj           the BFF (proxy, spec rewriting, Jellyfin proxy)
    ├── config.clj         env-var configuration
    └── dev.clj            shadow-cljs dev proxy predicate (not in the uberjar)
src/css/main.css           Tailwind entry and shadcn design tokens
```

shadcn ships React/TSX components, so the ones here are small Reagent
reimplementations that use shadcn's Tailwind classes, design tokens, and npm
helpers (`cva`, `clsx`, `tailwind-merge`).
