# Marquee plan

Last reviewed: 2026-10-13

This is the one place for ongoing work on Marquee: what's being worked on,
what's next, known issues, and the decisions that still shape the code. Delete
items once they're done (git history keeps them). Cross-repo priorities live
in the [ecosystem plan](https://github.com/fudoniten/pseudovision/blob/master/PLAN.md#ecosystem).

## Now

- [ ] Add GitHub CI that runs `nix flake check` (ecosystem step 4). Marquee
      has no checks defined yet, so start with `nix build .#bff`.

## Next

- [ ] Show TS's plan read API on the schedule page: frozen grid, feasibility
      report, and monthly overrides
      (`/api/scheduling/channels/:ch/{grid,grids,overrides,preview,plan}`).
      Today only pvcli reads it.
- [ ] Stop falling back to TS's `/api/genres` (`::load-genres-as-dimensions`)
      so TS can delete it as part of its dimension cleanup; genres are the
      `genre` dimension now.

## Later

- Split `events.cljs` (about 2,500 lines) into per-page event namespaces.
- When services start checking auth, turn the BFF's optional `*_TOKEN`
  headers into required configuration, and stop proxying `/api/jellyfin/*`
  with an added token for unauthenticated callers.
- Add a small test suite (at least for `routes.cljs` and the BFF's spec
  rewriting).

## Known issues

- The BFF is an open proxy: anyone who can reach it can call every
  downstream service with Marquee's tokens. Acceptable while
  `*.sea.fudo.link` is local-only behind NAT.

## Decisions

- 2026-10-13: Keep stack traces in BFF 500 responses while in active
  development, behind `ENABLE_UI_DEBUG_TRACES` (default on).
- Marquee talks to Tunabrain only through TS; the Tunabrain proxy exists for
  the API Docs page.
