# FINDINGS-650 — Javseen (javseen.tv) deep pass + in-run fix (approved #631 spec, self-contained run)

Run: 2026-10-16. Issue #650 (self-contained run of the approved #631 spec). Full-surface live probe + drift fix. **Verdict: `ok-drift` (confirmed fixed condition in-run), tier `plain-curl` on every surface (browser step never reached), drift_confirmed 0 → 1.**

## Phase 0 — calibration

Canaries 3/3 match (`audits/canaries.json`, last_verified 2026-10-16):

| canary | expected | observed |
|---|---|---|
| film1k-challenge-pair | plain 403 / TLS 200 | 403 / 200 ✔ |
| eporner-healthy | plain 200 | 200 ✔ |
| pandamovies-redirect-origin | plain 302 / TLS 200 | 302 → 200 ✔ |

Instrument healthy; no environment-degraded downgrade in effect.

## Probe evidence (all surfaces, plain-curl tier)

### Home pagination (recent + category + tag rows; p1 vs p2 must be disjoint)

| row | p1 | p2 | overlap (numeric card IDs) |
|---|---|---|---|
| `/recent/?ajax=browse_videos` | 200, 30 cards | 200, 30 cards | 0 |
| `/categories/solowork/?ajax=category_videos` p1/p2 | 200, 30 cards | 200, 30 cards | 0 |
| `/tag/uncensored/?ajax=browse_videos` p1/p2 | 200, 30 cards | 200, 30 cards | 0 |

Card grammar unchanged: `{"status":1,"html":…}` JSON with `li id=\"video-<N>\"` blocks (beware: bare `video-` is a class name on this site — cardinality must use `video-[0-9]+`).

### Search & quickSearch

- Search AJAX `search/video/?ajax=search_results&s=rookie&o=recent` p1/p2 → 200, 30+30 cards, **p1∩p2 = 0** (numeric-ID overlap). Provider's JSONObject→Jsoup path unchanged.
- Plain HTML search pages (`/?s=`, `/search/video/?s=`) still render a JS-driven landing page with **0 server-side results**; no suggest endpoint exists → **quickSearch correctly absent** (`hasQuickSearch = false`; no distinct quick-search endpoint was ever recorded for this provider).

### Video pages (6 probed, 3 fresh URLs + search/home-sourced)

`/287978/cover-curvy-body-and-sexy-legs/`, `/287981/`, `/287984/`, `/286955/`, `/287527/`, `/287983/` — all 200; choice rows `button.button_choice_server` 4–9 per page; titles/posters/plots on all 6 (`meta[property=og:*]` + `og:video:duration` + `og:video:release_date`).

**Deep-pass standing checks:**

- **#539 healed state holds**: durations real on every fresh page (7800/14640/12000/7380/7200 — no sentinel `0` with a true runtime). `/287527/` still ships the sentinel `content="0"` it shipped in September; the shipped `DurationParse` guard nulls it client-side — row behavior correct, no regression.
- **Video identity**: og title covers the full fanza-style title; poster `pics.javseen.tv/media/videos/tmb/000/…` per-page distinct; og:description carries the actor sentence (provider extracts actors from it, unchanged).
- **Related videos**: still absent (commented-out "No related videos found!" placeholder) — matches #118/#145; provider's `ul.videos.related li` yields an empty recommendations list, which is correct, not drift.

### Stream/embed resolution (the confirmed condition)

Data-embeds (base64 `data-embed` → JSON) decode to host rows on every fresh page:

| row | host | live state 2026-10-16 |
|---|---|---|
| primary | `cloudwish.xyz/e/<token>` | **200, packed eval player** — on every fresh page |
| 2 | `mycloudz.cc/v/…` | unpacks fine; CDN (`dramiyos-cdn.com` family) **502 from datacenter IP** — long-documented IP/geo gating, not drift |
| 3 | `streambeast.upn.one` / `streamtape.net` | 200 shell / 404 (fast-expiring tokens), unchanged |
| 4 | `playmogo.com` (dood row) | 301 → 403 Cloudflare for runner, unchanged |
| 5 | `javhdz.today` rows | **not present on any fresh page** — the `Javhdz`/`Javhdz2` extractors shipped for #145 are registered but unused today (harmless legacy) |

**CloudWish resolution (was the in-run fix):** the cloudwish embed unpacks (Dean-Edwards packed jwplayer) to a `links` object `{hls2: https://…premilkyway.com/hls2/...master.m3u8?t=…&e=129600…, hls3: …master.txt, hls4: "/stream/…/master.m3u8"}` (relative hls4 present on 4/6 pages; hls2-only on the rest). Live-verified streams: hls2 masters 200 `application/vnd.apple.mpegurl` on 287981/287984/286955/287983; hls4 relative paths resolve to `https://cloudwish.xyz/stream/...master.m3u8`, 200 `vnd.apple.mpegurl` on 287978 and 287527 (whose hls2 origin 500s — per-link flakiness on this origin, both link tiers healthy globally). Per-link 500s/502s observed on individual hosts are origin/CDN side, never page grammar.

## The confirmed condition and the fix

**Condition: `cloudwish.xyz` — the primary embed host on every fresh Javseen page — had no row in `shared/HostRegistry.kt`.** A `CloudWish` extractor existed only as a Mangoporn provider-local class (`Mangoporn/…/Extractors.kt`, shipped for #443). Javseen's `loadExtractor` therefore resolved cloudwish embeds to *no extractor at all* — in-app, the exact chain every probe uses to reach a `master.m3u8` was a dead end unless a niche row (stormbeast/streamtape-fast planes) was selected by hand. Not a page-grammar drift — a **shared-registry gap**; evidence shows the root cause lives in `shared/`, so the fix goes there (AGENTS.md — never copy-paste extractors between providers).

**Fix (TDD-first, red → green, pure functions + one extractor class):**

- `shared/src/main/kotlin/com/kraptor/CloudWish.kt` — `CloudWishParse` (pure: `unpack(packedJs)` standard Dean-Edwards descending-index replacement ported from Mangoporn's grammar; `links()` master.m3u8 matcher incl. `/stream/` relative hls4; `preferred()` hls4-first) + `open class CloudWish : ExtractorApi()` ported behavior-identical.
- Test vehicle: `shared/src/test/kotlin/com/kraptor/CloudWishParseTest.kt` against real fixture `shared/src/test/resources/cloudwish_embed.html` (fresh cloudwish embed page, ~16 KB as served, token `3gop55rnx9nd`, Javseen `/286955/`, fetched 2026-10-16). 6 tests green.
- **Test-discovered byte-level nuance (worth recording):** the fixture page's `links` carries hls2+hls3 only — no hls4 pair on that video; the `/stream/` hls4 shape was probed live on `/287984/`+/`287978/` and is covered by a synthetic test. Also, Mangoporn's original unpack regex used a capturing-group alternation `((?:[^'\\]|\\.)*)` — JVM java.util.regex recurses per group iteration and **overflows the stack on a real ~16 KB page (StackOverflowError in Pattern$Loop)**; Android's ICU engine is iterative, so the ported grammar worked in-app but could never pass a JVM fixture test. The promoted `CloudWishParse.unpack` extracts the pack arguments with strict greedy groups requiring the `'.split('|')` terminator (real page shape, one pack per page) — behavior-identical output on the real payload (verified byte-for-byte against the node unpacker transcript after escape normalization).
- `shared/src/main/kotlin/com/kraptor/HostRegistry.kt` — added the `CloudWish()` row (before `MyDaddyExtractor()`), the single registration point; all providers now resolve cloudwish embeds.
- `Mangoporn/src/main/kotlin/com/kraptor/Extractors.kt` — **deleted** (`git rm`), duplicate of the shared row; `Mangoporn.kt` registration narrowed to `listOf(Maxstream())` with an updated comment. No Mangoporn behavior change (same class, now from `shared/`).
- Version bumps: `Javseen` 19 → 20, `Mangoporn` 6 → 7 (the shared row serves both).
- Gates: `Javseen:test` + `Mangoporn:test` green (shared tests run per-provider); full `make` green across all 24 providers (honest gate — the shared splice touches every subproject).

## Verification (verify.sh, live, plain-curl tier)

Result: **video/stream/Load checks all pass**; 6 instrument-limited FAIL lines, all evidence-backed, none a provider-grammar defect:

1–6. search p1+page2 and home rows (recent p1/p2, category, tag) — verify.sh matches selectors on **literal HTML**; Javseen's only list surfaces are AJAX JSON whose `html` payloads are backslash-escaped (`\"video-…\"`), so the CSS-matching harness reads 0 even though every endpoint fetched 200. Manual transcript (same session): 30+30 cards per axis, **numeric-ID overlap 0 on all four axes**. This limitation was recorded verbatim in Javseen/FINDINGS.md at #145 and reproduced at #539's run; correcting it (unescape-JSON step in `check_listing`) is shared-script surface — proposed under the #631 epic, not actioned here.

Passes recorded: search/home GETs 200; 6 video pages 200 with 4–9 choice-button matches; **6/6 stream overrides served 200 `application/vnd.apple.mpegurl`** (hls2:hls4 mix, per-page distinct paths); load-response fields recommendations/tags/plot/duration/year/actors/posters all assigned in code. Music: quickSearch NOTE (correctly absent — no suggest endpoint). Related selector omitted (site exposes none — documented).

## Registry / lifecycle

- `providers.Javseen`: verdict `ok` → `ok-drift` (in-run fix awaiting merge), tier `plain-curl`, drift_confirmed 0 → 1, last_deep 2026-10-16, chronic false, standing_issue #477 unchanged; README verdict column updated to match.
- Dedup: standing Javseen issues checked — #145 closed, #539 **cleared** (healed-state check above: real durations on all fresh pages; the one page that still ships sentinel `0` is the known `/287527/`, correct under the shipped guard — **#539 stays closed, not refiled**); **#477 state re-checked live below** — remains standing, not drift, unchanged.
- #477 (mycloudz-family CDN 502s from datacenter IPs, long-standing IP/geo gating): reproduced exactly as recorded (mycloudz unpacks; `dramiyos-cdn.com` 502 for runner; cloudwish unaffected). Provider still resolves a full stream via cloudwish on every fresh page. **No new state to file; #477 remains the standing limitation, cleared by nothing** — verdict `ok-drift` records the code-side condition fix; the #477 note stays as site-side gate.
- Mangoporn dedup: its deleted local `Extractors.kt` shipped for #443 (cloudwish rows then absent from shared scope) — now superseded by the shared registry row; Mangoporn noted in this run's PR.

## Backlog (not this diff)

- verify.sh `check_listing` cannot parse JSON-wrapped listing payloads (`\"` attribute escapes) — shared-script improvement, echoed by the #649 poster/poster-agreement notes; belongs to the #631 epic.
- `Javhdz`/`Javhdz2` rows registered but no fresh data-embed references them today; removal candidate only if a future sweep observes a semantic absence (a page could reintroduce the row at any time).
- Per-link origin 500s (cloudwish hls2 on individual pages) are balanced by the hls4 plane; no retry logic needed in the extractor.

Ground truth: transcript artifacts in the run workspace; registry: `audits/findings.json`, `README.md`, `Javseen/FINDINGS.md`.
