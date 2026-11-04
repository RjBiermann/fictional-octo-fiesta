# FINDINGS-655 — ixiporn (ixiporn.org) deep pass + in-run shape-B guard fix

Run: attempt 2 (2026-10-17). Attempt 1 (https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/38994351736)
completed the full probe, filed finding #709, and reported identical results — its PR push died
rebasing `audits/findings.json` against the freshly-merged #652; this run re-delivers with a
conflict-free base (same probe conclusions, independently reproduced, plus the code-hazard fix
attempt 1 noted but did not apply).

## Verdict: `ok-drift`, tier `plain-curl`

Browser tier never reached; canaries matched 3/3 (film1k 403 plain, eporner 200, pandamovies 302).

## Deciding fact — redirect chain UNCHANGED

`ixiporn.org → 301 ixiporn.info → 301 ixiporn.live → 200` (`num_redirects=2`, effective URL
`https://ixiporn.live/`). Byte-for-byte the standing #468 condition (2026-09-24, re-confirmed
2026-10-14). Per the approved spec: **standing update, not a re-filed condition; no fix** for the
redirect; #468 stays a closed record; `drift_confirmed` stays 0.

## Full-surface re-check (all healthy on the `.live` origin, plain-curl)

| surface | result |
|---|---|
| home | 10 mainPage rows × p1/p2 = 20 fetches, 200 × 30 `div.video-block` cards each, 0 `data-post-id` overlap within every row (row1 latest p1∩p2 = 0 — #217 fix holds) |
| search | `/page/1?s=amateur` ×3 pages 200, 30 cards, fully disjoint; legacy `/search/<q>/page/N/` grammar also 200/disjoint; `/page/2/?s=` (slash form) returns the identical set |
| quickSearch | absent — theme `ftt_ajax_var` lazyload var only (`action=ftt_search` → 400); recorded absent, not drift |
| video | 6 shape-A pages (latest ×2 / tag-ullu / search / related ×2) 200, `meta[itemprop=contentUrl]` ×1, og:* + `duration`/`uploadDate` metas, `#video-tags` on 5/7 (page variance), related 12/page |
| stream | 6/6 `contentUrl` mp4s → **206 video/mp4** with `.live` referer (`ixifile.xyz`, `cdn2.ixifile.xyz`) |

verify.sh: **RESULT: PASS** (28 listing pages, 6 varied videos, stream serving + distinctness,
search↔load agreement, LoadResponse completeness). Harness NOTEs: quickSearch absent (explicit in
FINDINGS); actors unasserted — site does not expose actors (#127); card poster is a `data-src`
lazyload attr the regex-DOM instrument cannot attribute (posters empty = "not exposed" note;
provider's `jsoup` `a.thumb > img` unaffected).

## New record (independent reproduction of the failed attempt's #709 finding)

**shape-B pages (2021–2023 hub-embed era)** — 2/3 of searched-`amateur` video pages here; 3/7 in
attempt 1's sample — carry **no** `contentUrl` meta. The player is a base64-wrapped iframe:

```
<iframe src="https://ixiporn.live/wp-content/plugins/clean-tube-player/public/player-x.php?q=<b64>">
```

whose decoded payload (`post_id=…&type=iframe&tag=<iframe src="https://embed.redtube.com/?id=…">`)
frames an external **hub embed — hub varies per video** (xvideos in attempt 1's sample, redtube
here). Their duration meta is a site-side placeholder (`P0DT18H0M0S`). Getting streams for this
tier needs a b64→hub-resolver chain and is scoped to **#709** (1/3 sampled embed targets live
upstream there).

## Confirmed code hazard — fixed in-run, TDD (version 26 → 27)

`loadLinks` built stream URLs with
`selectFirst("meta[itemprop=contentUrl]")?.attr("content")?.trim().toString()` — on shape-B pages
`selectFirst` returns null, so `.toString()` produced the literal `"null"` and the provider
emitted `fixUrl("null")` — a **garbage dead stream entry** on every hub-embed video page.

Fix (minimal, in-provider): `IxiPornParse.contentUrl(element) → String?` pure Parse function
(trim, jsoup-decode, blank/`null`-guarded; null with no meta), `loadLinks` skips blocks without a
URL and returns `emitted` instead of unconditional `true`. JUnit4 `IxiPornParseTest` (6 tests)
written red (`Unresolved reference`) → green against fixtures copied verbatim from live blocks
(`/player-direct-newest-eyes.mp4block.html` shape-A, `/player-embed-redtube.block.html` shape-B).

## Gates

- `gradlew ixiporn:test` — 126 test executions (incl. 6 new), 0 failures (debug+release)
- clean `gradlew ixiporn:make` — green, `ixiporn/build/ixiporn.cs3` produced
- `scripts/check-findings.sh` (audit-providers lint) — PASS
- verify.sh — PASS (above)

## Registry & lifecycle

- `audits/findings.json`: only the `ixiporn` provider entry (verdict `ok-drift`, tier
  `plain-curl`, surfaces, `last_deep` 2026-10-17) + one `last_runs` entry (`run24`,
  `false_positives: []`). No fleet-wide rewrite.
- Standing #468: unchanged condition → not reopened, no new redirect finding.
- Embed-tier condition: already filed as **#709** by attempt 1 — dedup honored, no duplicate
  filed. This run's added evidence (redtube hub variant, `fixUrl("null")` hazard + fix) is
  recorded in `ixiporn/FINDINGS.md` and this file.

## Acceptance run (#781, 2026-10-21) — embed-tier green bar; out of chain order

Run: https://github.com/RjBiermann/fictional-octo-fiesta/actions/runs/39538785472 (05-implement on #781).
**Fired before its dependencies merged** — #778 (shared xvideos resolver), #779 (shared redtube
resolver + unknown-hub skip), #780 (ixiporn b64 chain) were all still OPEN at run time. The chain
was therefore exercised **live/at the site level**, not through provider code; the provider-code
green bar (IxiPorn b64 → hub adapter → playable stream in `loadLinks`) remains gated on those
merges and should be re-confirmed then. No provider code change was made this run (the #655
contract — shape-B pages emit nothing — holds and is correct for dead targets; see ratios below).
Branch note: the acceptance evidence below is what those merges must reproduce.

### Canaries + standing conditions

- film1k 403 plain / eporner 200 / pandamovies 301 — 3/3 match.
- `ixiporn.org → 301 ixiporn.info → 301 ixiporn.live → 200` (`num_redirects=2`) — #468 chain UNCHANGED, third consecutive confirmation.
- Latest pagination: p1/p2 × 30 `video-block` cards, `data-post-id` overlap **0** — the #217 fix holds.

### verify.sh — RESULT: PASS (exit 0), with the green-bar story included

Harness transcript: `audits/`-style run kept in the run (2 search + 2 home pages × 30 cards, disjoint;
6 varied videos — 3 shape-A direct + 3 embed-tier; streams 206 `video/mp4`/`mpegurl` with the single
global `Referer: https://ixiporn.live/`; tags/year/duration present on all 6; related 12 cards/page;
search↔load agreement incl. 2 NOTE-level poster variants; LoadResponse complete —
recommendations/tags/duration/year populated in provider Kotlin).

**Green bar: 3 embed-tier videos resolved end-to-end live (player-x b64 → hub embed → playable stream):**

| ixiporn page (shape-B) | hub target | resolved stream (harness-asserted) |
|---|---|---|
| /4k-close-up-full-video-clit-sucking-…-mrs | xv `58195613` | `mp4_sd.mp4` → **206 video/mp4** |
| /clit-licking-and-pussy-eating-until-… | xv `57351833` | `hls.m3u8` → **206 mpegurl** |
| /amateur-jocks-fisted-by-japanese-babes | rt `38920131` | rdtcdn `master.m3u8` → **206 mpegurl** |

Chain shape confirmed live (this is what #780 + #778/#779 implement):
1. `div.video-player` iframe → `…/clean-tube-player/public/player-x.php?q=<b64>` (200 on .live referer; the b64 wrapper is **universal** — shape-A pages carry it too with `type=video` payloads, so `contentUrl` metas and the player-x payload are two faces of the same player config).
2. b64 (URL-encoded, pad-if-needed) → `post_id=…&type=iframe&tag=<iframe src="HUB-EMBED">`.
3. Hub embed → stream: xvideos embedframe page exposes `mp4_sd.mp4` + `hls.m3u8` (referder-agnostic, 206 with or without any referer); redtube embed page exposes signed `videoUrl: /media/hls?s=<b64-signed>` on www.redtube.com whose JSON reply carries rdtcdn `master.m3u8` links (serves 206 with the plain ixiporn referer).

### Deleted-target ratio (this run's samples, plain-curl)

| hub | sampled embeds | live upstream | deleted upstream |
|---|---|---|---|
| xvideos (embedframe) | 37 | 4 (11%) | 33 (89%) — 22× 404/518B "no longer exists", 11× 200/1049B deleted-wrapper body |
| redtube (embed ?id=) | 2 | 1 (50%, rt `38920131`) | 1 (rt `21152821` — 404 "removed" wrapper, 26KB body; same target FINDINGS-655 sampled dead is re-confirmed) |

This deepens #709's "2/3 dead xvideos" observation to **89% on the wider sample** — the hub-embed tier is
predominantly a graveyard with a live minority, which is why the #655 contract (shape-B pages emit
nothing when the tier is dead) is the right decline, and why chain implementations must treat
"embed page resolves zero streams" as the single decline outcome for every dead shape (404 body,
deleted-wrapper body, and no-stream 200 body are all equivalent).

### Unknown-hub decline behavior (live)

- **videotxxx.com** (`/embed/<id>/?source=728113931`) — 4/50 sampled pages (10%) place it as a **RAW iframe directly in `div.video-player`**, NOT wrapped in player-x b64: `Amateur Anal Sexy MILF In HD`, plus 3 further pool-3 pages. Its embed replies with a canonical `txxx.com/embed/<id>/` SPA shell (88KB JS, no static stream markers — no mp4/m3u8/flashvars in HTML at all).
- Decline shape for #779: a hub outside the xvideos/redtube adapter set must **emit nothing** (no garbage URL — same contract as #655's contentUrl-null guard). For videotxxx the decline is additionally correct at the serving level: the shell carries no statically-extractable stream, so even a future adapter would need JS-side resolution (out of scope; recorded as evidence only).
- No further unknown hubs appeared in the remaining 46 pages (xvideos/redtube only).

### New junctions discovered live (evidence only — no fixing inside this run)

1. **player-x wrapper is universal**: shape-A pages carry the same `player-x.php?q=<b64>` iframe with `type=video` payloads whose `tag=` contains the self-hosted `<video>` element. #780 can extract from the payload or the `contentUrl` meta — they are the same player config; either is a valid single extraction point (the meta is the cheaper one, no fetch of player-x needed).
2. **redtube embed → www.redtube.com/media/{hls,mp4}?s=<signed> → rdtcdn master.m3u8 JSON**: the #779 resolver has TWO resolution depths available — direct `mp4`/`hls` markers are absent, but a single follow-up GET on the signed media path yields the real CDN URLs (quality list included: 240p/480p in the sample). Implementations should follow that hop rather than grep the embed HTML for CDN links.
3. **xvideos embed streams are referer-light**: `mp4_sd.mp4`/`hls.m3u8` served 206 with no referer AND with the ixiporn referer — no header work needed for the adapter (unlike the direct tier, which still prefers the .live referer per FINDINGS-655/verify history).
4. **Title-data inconsistency in the 2020 mrpussylicking imports (site-side, NOT provider drift)**: on the two sampled import-era pages the load page's own `a.infos`/`span.title` blocks show a different title ("Cum Blast in a Popular Amateur Porn Compilation") than the search card AND og:title for the same URL ("4K CLOSE UP FULL VIDEO! … – Amateur MrPussyLicking"); additionally card surfaces use `–` (entity `&#8211;`) where og:title uses `-`. The provider is unaffected (og:title on load, a.infos[title] on search); recorded because check-5 instrument tolerance had to learn it — see below.

### verify-provider harness delta (in-run, TDD red → green, selfcheck green)

Two false-FAIL instrument classes surfaced on the 2021-era sample (both pre-known families — this run extends the generic rules, NOT per-site allowlists; per-surface tolerance is the #680/#775 glossary position):

1. **WP thumbnail size tokens**: card posters serve `-400x225.jpg` sized crops while og:image serves the full-size file. `poster_path` now also strips a trailing `-NNNxNNN` token (new scheme `-\d+x\d+` alongside the `-t/-n/-x2` one-token rule; undashed stems untouched).
2. **Unicode-dash title variants**: check-5's `norm()` now canonicalizes `[\u2010-\u2015\u2212]` → `-` before comparison (title AGREEMENT only — inner_text/dups distinctness semantics untouched).

`verify_selfcheck.sh` extended: poster 400x225 equivalence / undashed-stem non-stripping / norm dash + whitespace-lower regression cases. Selfcheck RESULT: PASS, bash -n clean. Both files (`.pi/skills/verify-provider/scripts/verify.sh`, `verify_selfcheck.sh`) — no skill lock entry affected (skills-lock.json tracks external packages only).
