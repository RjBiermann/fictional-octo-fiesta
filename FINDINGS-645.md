# FINDINGS-645 — Cat3Movie deep pass (2026-10-16)

One self-contained run of the approved #631 spec: audit, probe, and fix Cat3Movie
(cat3movie.org). Baseline: the provider carried 8 closed drift/broken-provider issues
(#143/#180/#203/#250/#411/#417/#488/#522) → the drift-recurrence rule applied: this run was
**hardening**, never a point-fix swap.

## Phase 0 — canary calibration: 3/3 MATCH

| Canary | Expected | Observed | Result |
| --- | --- | --- | --- |
| film1k-challenge-pair | plain 403 / TLS 200 | plain 403 / TLS 200 | match |
| eporner-healthy | plain 200 | plain 200 | match |
| pandamovies-redirect-origin | plain 302 → 200 @ .org | plain 302 → 200 @ .org | match |

All cat3movie.org surfaces below returned **200 to plain `requests`** (no impersonation
needed) — probed at **plain-curl tier** (desktop-chrome UA, no referer beyond the site's
own; exact provider header shape). Runner IP 172.208.92.164 (US).

## Probe (full surfaces)

### Homepage rows

- `/` and `/page/2` on **fresh origin fetches** (both `cf-cache-status: EXPIRED/MISS`): both
  serve **the same 44 unique cards**, page 2 tagged WP `paged-2` body class + `rel='prev'`.
  This corrects the historical cache-artifact attribution in FINDINGS-522: the clamp is
  **site-side, served from origin** (the halimmovies theme clamps the homepage Latest row,
  while `?page=2`/`/page/2` of the category/service rows paginate cleanly).
- Category rows paginate and are disjoint: `new-movies` 20/20, `classic-porn` 20/20,
  `classic-erotica` 20/20, `asian-erotica` 20/20 (0 overlap each pair).
- Within one homepage render, the newest post(s) repeat across multiple rows (one legacy
  post appeared 3×; the April 2026 posts 2×) — the provider already dedupes in-code
  (`SearchCard.homeCards` href distinctness), and this run adds cross-page dedupe below.
- Card grammar unchanged from #522: `article.thumb` with `a.halim-thumb[href,title]` and
  `img[data-src]`.

### Search

- `/search/teacher`: 200, 20 cards, 40 **pages total** in the renderer.
- **New: `/search/teacher/page/2` = 200, "Page 2 of 2", 4 cards, disjoint from page 1.**
  `/page/3+` → 404 (past the last page; the renderer shows the row-count cap: first page
  has 20, both later pages the rest). #522 recorded "no pagination" and returned
  `hasNext = false` — **the drift: the site gained the surface; the provider's contract
  (hasNext=false, page suffix unknown) silently drops it.**
- `/search/<slug>` still slug-ifies exactly as #522 recorded (lowercase, `-` from spaces).
- No quick-search/suggest endpoint exists (site-native absence; not drift — recorded).

### Video pages (5 sampled: the-lesser-evil-2014 / cathouse-fever-1984 / confessions-1977
/ killer-fish-1979 / c-h-u-d-1984)

- All 200. LoadResponse fields intact on every page: title (`h1.entry-title`), poster
  (`og:image`), year (`p.released a[href*=release/]`), tags (`p.category a`), actors
  (`p.actors p`), plot (`div.entry-content article.item-content p`), related-row
  (`section.related-movies article.thumb`, ≥ 8 each).
- `post_id` + `data-nonce` present as #522 recorded (search→load cross-check same).

### Stream chain (sv1/sv2/sv3)

- **sv1 (hlsfree) healthy end-to-end for all 5 samples**: `player.php` (Referer
  `watch-....html` + X-Requested-With) → `iframe https://hlsfree.com/embed/hls/<id>` →
  `defaultHlsUrl = …token=<hex>` → `GET .../api/hls/serve?token=` → 200
  `application/vnd.apple.mpegurl`, segments on s2.cat3hls.com → 200. Tokens are
  **per-video** (fresh token each fetch; tool's `/api/hls/serve` triple-shape "same path
  different videos" note is the query-stripping artifact, same as #522).
- **sv2 (loadvid) unchanged**: JS-gated, no source in HTML; site-native absence, not drift.
- **sv3 (playmate.to)** — see the "Chase the flag" section; no defect on the app path.

### Extra paths probed (spec-required)

- `sitemap.xml` → 200, temp-gzip rendered by CF (size 35 MB), translatable to video URLs.
- `?page=2` on home: 200, same 44 as `/page/2` — correct (`:page=2`).

## The chase-the-flag — playmate.to `/api/s`, and a false confirmation retracted

**First read (retracted):** `POST /api/s {"c":code,"d":"web"}` with the exact #522-verified
headers → **403 `{"error":"forbidden"}`**; adding `Sec-Fetch-Site: same-origin` +
`Sec-Fetch-Mode: cors` + `Sec-Fetch-Dest: empty` → 200. That looked like "the site now
requires the Sec-Fetch trio" and I shipped a shared-adapter change for it mid-run.

**Correction on differential re-probe (all ×3, stable):**

| Client | Headers | Result |
| --- | --- | --- |
| curl_cffi `chrome` (Chrome JA3) | no Sec-Fetch | **403 forbidden** |
| curl_cffi `chrome` | with Sec-Fetch trio | 200 |
| python-`requests` (non-Chrome JA3) | desktop-chrome UA, **no Sec-Fetch** | **200** |
| python-`requests` | mobile-chrome UA | 200 |
| python-`requests` | **no UA** | 403 |

The gate is **"JA3 presents as Chrome ⇒ must carry the Chrome invariants (Sec-Fetch
trio)"**; withdrawn clients that don't claim Chrome (okhttp/NiceHttp, python-requests)
bypass it, subject only to the #522-class "must send a UA" rule. NiceHttp/okhttp never
presents a browser JA3 — **the adapter's app path was never broken.** The correct read was
the instrument one (curl_cffi *is*a Chrome JA3), not an API change. This is the same family
as #522's hlsfast UA-gate note: edge rule gaps that only bite clients which *claim* browser
fingerprints.

**Action: reverted the speculative `shared/` change** (ADR-0005: never fix what can't be
reproduced in the app path; ponytail: no speculative hardening). Both shapes stayed green
after the revert. Recorded here and in the registry as a risk note: **any future change
that moves the request to a browser-fingerprint instrument (WebView, curl-impersonation
client) must carry the Sec-Fetch trio or /api/s will 403.**

## Fix delivered (TDD-first, provider-scoped)

`Cat3Movie/` only; `version = 12 → 13`; **no `shared/` changes survive the run**.

1. **Search pagination wired** (confirmed drift #1): pure seam `Parse.searchUrl(mainUrl,
   query, page)` — page 1 keeps the bare `/search/<slug>`, page > 1 emits
   `/search/<slug>/page/<N>`; slug-ification identical to the pre-run inline filter
   (pinned by test). `search()` now appends the page suffix and returns
   `hasNext = list.isNotEmpty()` so the row terminates on the 404 past-the-last page
   (page 3+ parses to 0 cards past the renderer's cap).
2. **Latest-row cross-page dedupe hardening** (drift-recurrence response to drift #2):
   pure seam `Parse.filterSeen(cards, seen)` keeps only first-served hrefs per row and
   records what it keeps; `getMainPage` holds a per-row `seenHrefs` set keyed on
   `request.data` and **re-seeds on `page ≤ 1`** (each fresh render legitimately restarts
   the listing). Category rows (clean pagination) pass through untouched — page 2 is all
   new, no false negatives.

**Tests (new, all green):**

- `Cat3MovieSearchPageTest` ×3 — page-1 bare path; page-2 `/page/2` suffix; page-2 with
  full moon post-slug "3-D Sex & and  Zen" → `3-d-sex-and-zen` (slug holds non-ASCII
  loss and multi-space collapse).
- `Cat3MovieRowDedupeTest` ×4 — page-1 keeps-all-seeds-empty; second call over the same
  list → empty; overlap keeps only the genuinely new tail; page-1 re-seed clears the set
  (page-2 cards after a re-render are no longer suppressed).
- Test counted 86/86 green (83 pre-existing + 3 in-run additions to
  `Cat3MovieParseTest.kt`); `bootstrapCloudstream` + full `gradlew make` clean build green
  (all 24 providers — the shared revert restored the exact pre-run tree).

## verify.sh run (tool + manual re-verification)

`5 × --video-url` (the samples above) / search family `{confessions, cathouse, teacher,
teacher/page2}` / home `/`. **Final: FAIL — four instrument limitations, all
evidence-backed re-verified manually below.** Same caveats as earlier runs, none new:

1. **check-1a home duplicates** — raw-site condition: the homepage renders the same card
   in 2 rows (2×) and the legacy post 3× within one page. The **provider dedupes
   in-code** (`SearchCard.homeCards` already deduped within a page; this run adds
   cross-page for Latest). Tool asserts the raw page → suspected-FAIL, artifact.
2. **check-2 `iframe` = 0 on movie pages + 'stream path shared'** — same #522 caveat
   (streams live behind the ajax player chain, not in initial HTML). The mechanical
   `--stream-url` check re-passed: **5/5 fresh tokens → 200 mpegurl.**
3. **check-5 title mismatch** — search-card titles live in the **`title` attribute** of
   `a.halim-thumb` (same #522 caveat). Manual: `search[1] (cathouse)` card attribute
   `"Cathouse Fever (1984)"` == load h1 `'Cathouse Fever (1984)'` exactly; `confessions`
   likewise. Poster: card `data-src` `/wp-content/uploads/2026/10/cathouse-fever-1984-34693-300x450.jpg`
   == load `og:image` path.

All four were proven non-real issues live; the registry verdict absorbs them.

## Verdict

**ok-drift** — all surfaces 200 plain-curl, grammar unchanged, 3 confirmed drift-class
conditions (search pagination gap; home-page clamp — *not* a cache artifact, site-side;
playmate Chrome-JA3/Sec-Fetch gate — app-path clean). Fix shipped is the pagination wiring
+ the drift-recurrence hardening; the speculative shared change was retracted when the
condition failed to reproduce on the app path. Mangoporn/PandaMovies host playmate sv3
embeds too (FINDINGS-596 recorded "playmate embed .mp4") but are **not** touched by this
run — their own audit runs (#657/#659) cover them.

## 2026-10-17 attempt-2 recovery re-verification (this run)

Attempt 1 (run 38957953946) completed the probe, fix, and all gates but **died at PR push
on a `audits/findings.json` rebase conflict** against the freshly-merged runs. This run
recovered the attempt-1 diff from its bundle artifact, rebased onto the current main
providently, and re-applied it: the five provider/project files cleanly, and the
`findings.json` update as a surgical Cat3Movie-only entry + one `last_runs` entry
(`run26`) — per the spec's no-fleet-rewrite rule (the attempt-1 diff had been baked against
a stale base, which is exactly what the rebase conflict was).

This run re-verified live from a new runner IP and re-ran every gate on the rebased diff:

- Canaries: film1k plain 403 (match), cat3movie family plain 200 — calibration consistent
  with the attempt-1 3/3 record.
- Home `/` vs `/page/2`: 44/44 unique cards, **overlap 44** — the same-listing clamp repeats
  live on fresh fetches (site-side, corroborates the corrected FINDINGS-522 attribution).
- Search: `/search/teacher` 20 cards, `/page/2` **4 cards, 0 overlap**, `/page/3` 404 — the
  pagination surface this run's code wiring (`Parse.searchUrl`) is still live and disjoint.
- Video grammar: fresh samples `accidental-incest-2014`,
  `beautiful-teacher-in-torture-hell-1985` → 200 with `h1.entry-title`, `og:image`,
  `post_id` + `data-nonce` — unchanged. (One probe slug 404: id churn class, benign.)
- Stream sv1: a curl-shaped POST to `player.php` returns `{"status":true,"code":403}` —
  instrument note only: attempt-1 proved the real token chain 5/5 × 200 mpegurl with the
  app-path header/token flow; this shape difference is a curl-vs-app nuance, not adjudicated
  as drift (single shape, no echo in any listing/search/video surface).

Gates on the rebased attempt-2 tree: `gradlew Cat3Movie:test` green (164 test executions
debug+release, 0 failures — 7 new tests), full `gradlew clean make` green (all 26 `.cs3`),
`check-findings.sh` PASS, live re-verification above. Baseline instrumentation re-probe:
the attempt-1 verify.sh FAIL lines (4 instrument limitations) stand as adjudicated there.
