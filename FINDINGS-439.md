# FINDINGS-439 — PandaMovies: "search broken"

Issue cites the site search URL `https://pandamovies.pw/?s=rocco%27s+intimacy` ("rocco's
intimacy"). This is the second report of the same symptom with the same query: #425
("search not giving same result seen in site") audited the identical query on
2026-09-16 and concluded **not reproducible** — provider output indistinguishable from
the site (8 cards, same order/title/href/poster). One day later the maintainer filed
#439 with the same query. So this is search-drift recurrence #1 for the provider
(#425 closed same-day, non-repro, shipped an unrelated `hasNextPage` pagination fix).

Probed 2026-09-17 ~03:45–04:15 UTC. All requests plain curl / plain OkHttp
(5.0.0-alpha.12 — same HTTP stack as NiceHttp/CloudStream), unauthenticated,
no special headers.

## 1. The exact query works, repeatedly, from runner egress

30+ probes of `https://pandamovies.pw/search/rocco%27s+intimacy` (the exact URL the
provider builds for this query: `URLEncoder.encode("rocco's intimacy")` →
`rocco%27s+intimacy`, space→`+`, apostrophe→`%27`) over ~30 minutes with 3–20 s gaps —
every probe HTTP 200, every body carries the same **8 cards** (`div.ml-item`,
`a.ml-mask[oldtitle]`): Rocco's Intimacy, Rocco's Intimacy 2, Rocco's 4 Cams POV,
etc. Spinner variance: none seen.

- Plain curl: 200, 8 cards.
- OkHttp (app stack): 200, 8 cards, `oldtitle` present.
- UA variants (`okhttp/4.x`, `Dart/3.9`, empty, desktop Mozilla): all 200.
- Compressed / HEAD / HTTP2-negotiated: 200.

## 2. Both search endpoints equivalent and healthy

The issue links the WP-native `?s=` endpoint; the provider uses the theme's
`/search/{q}` endpoint. Compared across 8 queries (milf, pov, virgin, stepmom, x,
"japanese schoolgirl", n, cited query) — **card counts identical on every pair**
(40 or the true total on both). `?s=lesbian&paged=2` serves 40 cards, so the ?s=
endpoint paginates the same way (`?...&paged=N`). No asymmetry to exploit or fix.

## 3. Runtime path verified end-to-end at fixture level

Saved a real probe of the exact search URL, parsed with the provider's actual
`Parse.cards` (unit-test run against the live capture): 8 cards, correct titles, hrefs,
posters — including the `oldtitle` → h2 fallback path. Result:

```
CARDS=8 [Card(title=Rocco’s Intimacy, href=.../watch-roccos-intimacy-movie-online-free,
poster=https://i2.wp.com/pandanetwork.club/.../1376910h.jpg), ...]
```

Downstream of search also healthy: both `watch-roccos-intimacy*` pages serve 200 with
3 `a[id=#iframe]` embeds each (LuluStream/…, all registry-covered).

## 4. Deplyed artifact is current — not a stale-plugin explanation

`plugins.json` on the `builds` branch lists PandaMovies v2, sha
`4d6d5051…`; downloaded the actual `PandaMovies.cs3` and confirmed the manifest
(`{"name":"PandaMovies","version":2,"pluginClassName":"com.rjbiermann.PandaMoviesPlugin"}`).
Master (commit `2dbd1c5`, merged 2026-09-16 after the #425 audit) is what ships.

## 5. Unit tests and build

`./gradlew bootstrapCloudstream && ./gradlew PandaMovies:test` — all green
(existing parse/page suites pass unchanged against current code).

## Eliminated hypotheses (verified here, in addition to most of #425's list)

- Query encoding: `+` and `%27` vs `%20` variants, curly-apostrophe keyboard input
  (`rocco’s intimacy` → `%E2%80%99`: site returns *more* results, fuzzy match, 48 —
  still results, still 200; not a zero-results bug).
- Search-page 2 of the cited query: 404, but with 8 cards `hasNextPage` is false and
  the app never requests page 2 — no tail error possible.
- CLoudflare / region / UA: no challenge, all UAs 200 (from this egress; a user-region
  difference cannot be reproduced from the runner — see gap below).
- WP caching flakiness: single-entry `cache-control: max-age=3` observed, but 30+
  probes show no degraded/empty serving; raw `< ?php` fragments in the markup are a
  longstanding theme quirk already present in the #421-era fixture
  (`panda-search.html`), not new corruption.

## Conclusion

**Not reproducible as reported** — same verdict as #425, now on the day-old merge of
that audit's own shipped code. Drift recurrence is 1–2 (one closed non-repro issue,
this open), not yet the 3-closed threshold that mandates structural hardening, and
every probe here is healthy, so there is no evidence to justify any specific selector
or endpoint change: any "fix" would be a gamble against a broken thing we cannot name.

## Recommendation

No code change in this PR; the maintainer decision this needs is in-app evidence that
only a device can produce (in-app testing is maintainer-only at merge time, AGENTS.md):

1. In the app, note whether search returns 0, a wrong list, or errors.
2. If a failing capture exists (app version, timestamp, region), attach or quote it —
   with a failing timestamp we can diff the site's response for that exact moment
   (`cache-control: max-age=3` + CF in front mean transient bad-cache windows are the
   one mechanism we cannot observe after the fact).
3. If search shows results but playback fails, that is a different surface (extractor),
   not `search()` — file against the watch page instead.
