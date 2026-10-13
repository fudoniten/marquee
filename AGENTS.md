# AGENTS.md — Marquee

> Working notes for AI agents and humans changing this repo. Start with
> [README.md](README.md) (what Marquee is, how to run it) and
> [PLAN.md](PLAN.md) (what's being worked on).

## Where Marquee fits

```
   Browser
     │
     ▼
   Marquee  (SPA + BFF, this repo)
     │ proxy /api/<service>/*
     ├──► Pseudovision      channels, playout, media libraries, jobs
     ├──► Tunarr Scheduler  catalog metadata, tags, grids, guidance, jobs
     ├──► Grout             filler/bumper clips, directory profiles
     ├──► Tunabrain         OpenAPI docs only (AI calls go through TS)
     └──► Jellyfin          images and direct links (optional)
```

The SPA never calls a service directly. Everything goes through the BFF,
which holds credentials and serves rewritten OpenAPI specs.

## Conventions

- **All HTTP dispatch lives in `events.cljs`**, as martian requests keyed by
  operation id (`{method}-{path-with-dashes}`, e.g. `GET /api/media/libraries`
  → `:get-api-media-libraries`). Path params go in the params map under their
  segment name (`{:id 123}`).
- **One namespace per page** under `pages/`, registered in the page table in
  `views.cljs` and given a URL in `routes.cljs`. List-view state (library,
  page, filter) rides in the query string so reload and Back work.
- **shadcn components are copied in and owned** under `components/`, not npm
  deps.
- **Kebab-case keys.** PV and Grout emit kebab-case JSON (`has-more`, not
  `has_more`); read what the wire actually carries.

## Common pitfalls

1. **Don't point martian at a service's own `/openapi.json`.** The generated
   client would call the public hostname directly and hit CORS. Always load
   `/api/<service>/openapi.json` from the BFF, which rewrites every path to
   route through itself.
2. **Operation ids come from the path.** Renaming an upstream route breaks
   every caller in `events.cljs`. Prefer adding routes to renaming them.
3. **Two lockfiles.** After changing `deps.edn` or `package.json`, run
   `nix run .#update` and commit both `deps-lock.json` and
   `package-lock.json`; the Nix build is offline and fails on a stale lock.
4. **The BFF and shadow-cljs both default to port 8080.** In dev the BFF must
   run on 3000 (`PORT=3000 clojure -M:server`); `nix run .#dev-server` does
   this for you.
5. **`server/dev.clj` is dev-only.** It's the shadow-cljs proxy predicate and
   is deleted before the uberjar build; don't require it from `server/core.clj`.
6. **Use `nix develop`, not `nix shell`**, to get the full toolchain.

## Testing

There are no automated tests. Exercise changed pages against the deployed
services with `nix run .#dev-server`, and confirm `nix build .#bff` still
succeeds before opening a PR.
