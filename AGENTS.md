# AGENTS.md — Marquee

> Working notes for AI agents and humans working on this repo.
> For the integration walkthrough, see [INTEGRATION.md](INTEGRATION.md).

## What this is

Marquee is the **web UI** for the Fudo stack. It is a ClojureScript SPA
compiled with **shadow-cljs** and styled with **Tailwind + shadcn/ui**, plus
a small Clojure BFF (Backend-for-Frontend) that proxies requests to the
downstream services and holds credentials server-side.

It currently surfaces:

- A **Media** tab — browse media libraries and items from Pseudovision, view
  per-item metadata from both Pseudovision and Tunarr Scheduler
- A **Media Detail** page — combined view of one item

Tunabrain integration is scaffolded but not yet wired into the UI.

## How it fits in the ecosystem

```
   Browser
     │
     ▼
   ┌──────────────────────────────────────────┐
   │  Marquee (this repo)                    │
   │  ├── SPA  :8080  (shadow-cljs + Tailwind)│
   │  └── BFF  :3000  (Clojure, http-kit)     │
   └─────┬─────────────────┬──────────────┬──┘
         │ proxy           │ proxy        │ (planned)
         ▼                 ▼              ▼
   Pseudovision      Tunarr Scheduler  Tunabrain
   (catalog, media)  (metadata)       (categorisation)
```

- **Pseudovision** is the primary read source (media libraries, items, channels).
- **Tunarr Scheduler** is a secondary read source for the LLM-derived
  metadata that Pseudovision itself doesn't carry.
- **Tunabrain** is planned (env vars are wired up; UI hooks are not).
- **The BFF holds all downstream credentials** — the SPA never sees them.
  The BFF also rewrites each service's OpenAPI spec to route through itself
  before the SPA uses `martian-re-frame` to auto-generate clients.

## Live endpoints (cluster)

| Service | URL | Notes |
|---|---|---|
| Public HTTPS | `https://marquee.kube.sea.fudo.link` | Ingress via cert-manager |
| SPA root | `GET /` | Serves the compiled CLJS + Tailwind bundle |
| BFF | internal to the same pod; `:3000` in dev | Proxies `/api/{pseudovision,tunarr-scheduler,tunabrain}/...` |
| OpenAPI | not at root | The BFF serves per-service OpenAPI at `/api/{service}/openapi.json` for client regen |

**Deployed as of 2026-07-03:** `f2118ef` (last commit 2026-06-21). Frontend
and BFF are bundled into a single Nix-built image.

## Local development

The dev environment needs three terminals (frontend, BFF, Tailwind watcher).
Use `nix develop` to get all tools, including `clj-nix` for the Maven lockfile
and the Node toolchain.

```bash
# First-time setup
nix run .#update                # regenerate deps-lock.json + package-lock.json
npm install                     # populate node_modules

# Terminal 1 — frontend (hot reload)
nix develop
npx shadow-cljs watch app
# → http://localhost:8080

# Terminal 2 — Tailwind CSS watcher
nix develop
npx tailwindcss -c tailwind.config.js \
                 -i src/css/main.css \
                 -o public/css/main.css \
                 --watch

# Terminal 3 — BFF
source setup-env.sh             # or `direnv allow` if using .envrc
clojure -M:server
# → http://localhost:3000
```

For the BFF, you need `PSEUDOVISION_URL`, `TUNARR_SCHEDULER_URL`,
`TUNABRAIN_URL` (and tokens, if any). See `.envrc.example` or
[INTEGRATION.md](INTEGRATION.md).

Production build:

```bash
nix build      # → ./result (the static site + the BFF binary)
nix run        # serves it on :8080 (override with PORT=...)
```

## Source layout

```
src/
├── marquee/                ; ClojureScript SPA source
│   ├── core.cljs           ; app entry / re-frame registry
│   ├── events.cljs         ; re-frame events (HTTP dispatch lives here)
│   ├── subs.cljs           ; re-frame subscriptions
│   ├── views.cljs          ; top-level routing
│   ├── pages/              ; one ns per page
│   │   ├── media.cljs
│   │   └── media_detail.cljs
│   ├── api/                ; martian-re-frame instance definitions
│   └── components/         ; shadcn/ui-style wrappers
├── css/
│   └── main.css            ; Tailwind entry
└── marquee/server/         ; Clojure BFF source
    └── core.clj

public/                     ; static assets, compiled CSS output
shadow-cljs.edn             ; CLJS build config
tailwind.config.js
postcss.config.js
deps.edn                    ; Clojure deps (frontend + BFF aliases)
package.json                ; Node deps (react, tailwind, shadcn helpers)
flake.nix / flake.lock      ; Nix packaging
```

## Public API surface (BFF)

The BFF is internal — the SPA is the only legitimate client. The BFF exposes:

| BFF path | Forwards to |
|---|---|
| `/api/pseudovision/*` | `PSEUDOVISION_URL/*` (rewrites OpenAPI through itself) |
| `/api/tunarr-scheduler/*` | `TUNARR_SCHEDULER_URL/*` |
| `/api/tunabrain/*` | `TUNABRAIN_URL/*` (planned) |
| `/api/{service}/openapi.json` | The original OpenAPI, rewritten to route through the BFF |

The SPA talks to the BFF using `martian-re-frame`. Operation IDs follow
`{method}-{path-with-dashes}` — e.g. `GET /api/media/libraries` →
`:get-api-media-libraries`. The PATH parameters are passed in the params map
under their segment name (e.g. `{:id 123}`). See
[INTEGRATION.md](INTEGRATION.md#operation-id-format) for the full pattern.

## Common pitfalls

1. **The BFF rewrites OpenAPI — do not consume the upstream spec directly.**
   If you point `martian-re-frame` at `https://pseudovision.kube.sea.fudo.link/openapi.json`,
   the generated client will hit the public hostname and you'll get CORS /
   401 / cross-pod issues. Always consume
   `http://localhost:3000/api/pseudovision/openapi.json` (dev) or the
   equivalent on the cluster, which has all paths rewritten to route through
   the BFF. This was the root cause of the `marquee-operationid-fix` case
   study (June 2026); see
   `references/marquee-operationid-fix-case-study-june-2026.md` in
   `martian-openapi-debugging`.
2. **Two lockfiles, both required.** `nix build` refuses to run if
   `deps-lock.json` or `package-lock.json` is out of date with `deps.edn` /
   `package.json`. After changing either manifest, run `nix run .#update` and
   commit both lockfiles.
3. **Operation IDs are derived from the path, not the handler.** If you
   rename a route, every caller breaks. Prefer adding new routes to renaming
   old ones; the operation ID format is documented in INTEGRATION.md and
   used throughout `events.cljs`.
4. **The BFF holds credentials; the SPA does not.** Tokens are
   server-side only. Don't `console.log(import.meta.env)` in the SPA — they
   won't be there. The `.envrc` / `.env` files are `.gitignore`'d for a
   reason.
5. **Tailwind + shadcn is not a re-frame component library.** shadcn-style
   components are *templates* you copy into `components/` and own. Don't
   treat them as npm deps.
6. **`nix run .#update` is needed on the first build.** The first `nix
   build` of a fresh checkout will fail with a lockfile error pointing at
   `clj-nix`. Run `nix run .#update`, commit the lockfile, then retry.
7. **The dev shell starts in `nix develop`, not `nix shell`.** `nix shell`
   gives you the closure outputs but not the shell hooks for path
   setup. Use `nix develop` for the full environment (clj, jdk, node,
   shadow-cljs).

## Where to look next

- `INTEGRATION.md` — the full Pseudovision + Tunarr Scheduler + Tunabrain
  integration walkthrough, including BFF design and operation-ID format
- `README.md` — Nix-based build and dev workflow
- `shadow-cljs.edn` — CLJS build definitions
- `src/marquee/events.cljs` and `subs.cljs` — the canonical examples of
  how the SPA talks to the BFF via martian
- `references/marquee-operationid-fix-case-study-june-2026.md` (in
  `martian-openapi-debugging` skill) — what goes wrong when you bypass
  the BFF
