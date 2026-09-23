# FINDINGS-457 — PandaMovies (pandamovies.pw): connection timeout / no results

Probed 2026-09-23 (UTC ~20:00). Plain curl from runner + independent fetch via `r.jina.ai`
(different egress) for cross-check. Issue: "Connection timeout. No results. No search results."

## Verdict: the site is down, it is not a provider-code bug.

## Evidence

1. **Every path returns Cloudflare `522`** (edge→origin connection timeout), cached-front-page
   exception none. HTTPS handshake to the Cloudflare edge succeeds; the request itself hangs
   then 522s:
   ```
   $ curl -4 -A "Mozilla/5.0 (Windows NT 10.0; …) Chrome/124" https://pandamovies.pw/        → 522 "error code: 522" (body)
   $ curl -4s -o /dev/null -w "%{http_code}" https://pandamovies.pw/movies                 → 522
   $ curl -4s -o /dev/null -w "%{http_code}" https://pandamovies.pw/search/lesbian         → 522
   $ curl -4s -o /dev/null -w "%{http_code}" https://pandamovies.pw/?s=lesbian             → 522
   … re-probe 30s later: / → 522 (stable, not transient per-request jitter)
   ```
   Base `/products` earlier curl without UA: TLS completes (`TLSv1.3 handshake done`),
   `Operation timed out after 15002 ms with 0 bytes received`. DNS resolves normally
   (104.21.83.42 / 172.67.212.26, Cloudflare). So: edge alive, **origin server unreachable**.
   IPv6 resolves (2606:4700:…) and fails identically.
2. **Independent network agrees**: `r.jina.ai/https://pandamovies.pw/` → 422, log shows
   `page.goto: Timeout 15000 ms exceeded`. Not a runner-region / IP-block artifact.
3. **No live successor domain.** Candidate mirrors all fail or park:
   - `pandamovies.net`, `.co` → HTTP 200 but body is a pure domain-parking `window.location.href="/lander"` page.
   - `pandamovies.com` → 301 to `www.pandamovies.com` → **403 Cloudflare "Just a moment…" challenge** (no site content reachable).
   - `pandamovies.to/.org/.info/.site`, `pandamovie.net`, `thepandamovies.com`, `pandamovies.pz/.be/.art` → connection failures / 000.
   - Web search (Bing via r.jina.ai, Mojeek) surfaced no relaunch domain.
4. **Wayback timeline**: CDX shows last successful 200 snapshot **2026-07-22**; nothing since
   (~2 months). Site's origin died around/after late July 2026. Note the in-directory
   `PandaMovies/FINDINGS.md` carries a 2026-09-16 probe date from the earlier #421-creation-era
   note keeping, which does not match the outage window this probe observed — the outage window
   is bounded by Jul 22 (last good archive fetch) and today.

## Provider drift history (Anderson context)

Closed broken-provider issues for PandaMovies: #425, #439, #444 — and now #457 → **four**.
Per AGENTS.md "Drift recurrence means brittleness": at 4+ the provider is **Chronic** — a
removal candidate, and removal stays a maintainer decision (`ai-remove-site`).

## What was NOT done, and why

- **No code fix shipped**: the root cause is the dead origin, not parsing. Retry loops,
  longer timeouts, backup URLs — none of it rescues a 522 origin, and AGENTS.md forbids
  hardening changes that don't address evidence-backed root causes. `search`/`getMainPage`
  already throw cleanly into Cloudstream's normal error handling (users see the timeout,
  which is what happened here).
- **No removal**: Chronic status means removal is the maintainer's call (`ai-remove-site`),
  not the agent's.

## Sidecar: upstream pre-release pin refresh (required to run any local verification)

`bootstrapCloudstream` refused to run: upstream refreshed the moving `pre-release` tag between
2026-09-18 (old pin bbd246ed…) and today (new e984bf17…). Diff reviewed via
recloudstream@903ef471 → one file, `app/.../ui/player/FullScreenPlayer.kt` (subtitle-delay sign,
no provider-facing API change); rest of the roll is Weblate i18n. Bumped `expectedSha` in root
`build.gradle.kts`; `PandaMovies:test` green (`BUILD SUCCESSFUL`). This is the "required by all"
exception to root-file restraint — the stale pin blocks every provider's build.

## Recommendation for maintainers

Treat #457 as the trigger for `ai-remove-site` on PandaMovies (site dead ~2 months, successor
domain unverifiable), or wait for revival. The provider directory is otherwise current
(version 3, hardened at #425/#444) and comes back for free if the site returns.
