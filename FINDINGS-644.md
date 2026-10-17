# FINDINGS-644 — FullPorner deep pass (issue #644, sub-issue of epic #631)

Run date: 2026-10-16. Tier: **tls-impersonated on every surface** (plain curl is
challenge-walled on fullporner.com *and* xiaoshenke.net from this runner —
`cf-mitigated: challenge`, suspected runner-IP class per #461, residential differential
unavailable from CI; TLS-impersonated curl_cffi/chrome is the evidence tier; browser step
never needed). Runner IP country: **US** (ipinfo, AS8075). Canaries 3/3 match: film1k 403
plain / 200 TLS, eporner 200 plain, pandamovies.pw 302 plain → 200 @ pandamovies.org.

Fixtures: live captures saved as `FullPorner/src/test/resources/embed_page_mask14.html`
(embed `/video/0739433/14`), `embed_page_mask2.html` (embed `/video/e98d5f2ef1da6/2`),
`watch_page_lacey.html` (`/watch/6ad215650e5f775a6fbbc65a`), 2026-10-16 evidence.

## Engine fingerprint

Custom xiaoshenke app (`static.xiaoshenke.net/css/osahan_v2.css`, layout + shared
`custom_v4.js`), full-SSR listing pages. Same suite as Film1k/FreePornVideos/Javbangers.
**Embed grammar CHANGED since every prior record:** the `xiaoshenke.net/video/<id>/<n>`
page no longer serves a jwplayer `configs` setup — it now renders a fluidPlayer playlist
from two static JS locals (see Stream sources). Unchanged: RTA meta, 24-card listing rows.

## Home pagination (getMainPage rows)

`/home/1` (Featured), `/category/hd-porn/2` (HD) — provider URLs `${mainUrl}/home/${page}`
and `/category/<slug>/${page}` — both 200 TLS, **24 cards/page, p1∩p2 = 0 on both rows** ✓
(card joins: `div.video-block div.video-card`, title `div.video-video-title a` shape =
`div.video-card-body div.video-title a`, poster `div.video-card-image a img` with
`data-src` real thumb, `src` blank.gif placeholder). Past-the-end behaviour not re-probed
(run13 2026-10-10 recorded 200-clamp; unchanged class).

## Search

`GET /search?q=hardcore&p=1|2` → 200, **24 + 24 cards, p1∩p2 = 0** ✓. Query matching a
sampled video (`teacher+takes+control`) also 200 with the sampled card present —
agreement exercised (verify.sh check 5 silent pass). No JSON API; full-SSR listing page.

## Quick search — ABSENT

`/search/suggest?q=amateur` is **not** a distinct suggest endpoint: it renders the regular
search page querying the literal word "suggest" (canonical `/search?q=suggest`, 0 result
cards, 9 KB). `/api/search`, `/wp-json/wp/v2/search` → 404. Provider keeps
`hasQuickSearch = false` (absent surface recorded, not drift).

## Video pages (3 sampled, different listings)

All 200 TLS, same grammar:

| page | listing | h2 title | tags | duration | create epoch |
|---|---|---|---|---|---|
| /watch/68dbb83586d5fe1a7e1a6be8 | search "amateur" | Raw Passion Unleashed: Hardcore Amateur Blowjob | 3 | 22:34 | 1759312034 (2025-10-01Z) |
| /watch/6abe4b962f78355914467fc2 | search "amateur" | Teacher Takes Control with Her Massive Strap-On | 12 | 40:25 | 1790869076 (2026-10-01Z) |
| /watch/6ad215650e5f775a6fbbc65a | home p1 | Lacey Deepthroats Massive Cock With Her Juicy Big Ass And Tits On Full Display | 6 | 50:44 | 1792160596 (2026-10-16Z) |

Exposure inventory: title ✓ (`single-video-title h2`); plot ✓ (`meta[name=description]`,
fixed suffix " on fullporner.com, the best full length porn site." — already handled);
tags ✓ (`p.tag-link span a`, `#tag` text — **#526 condition live-confirmed FIXED**: the
`p.tag-link` paragraph sits inside `div.single-video-title`, so the shipped selector
`…div.single-video-title p.tag-link span a` matches all 3 live pages; issue closed, no
dedup/file needed); **duration ✓ (`div.video-info > div:last-child` h:m:s clock) and
upload date ✓ (`span.create` epoch seconds) — NEWLY EXPOSED vs #526's "duration not
exposed / no upload-date" record, unparsed by the provider → data-completeness gap**;
actors: `single-video-info-content p a` present-but-empty on all 3 (site renders the
Pornstar: cell only where a pornstar exists — provider's parseActors filters blanks, kept);
recommendations ✓ (`div.video-recommendation div.video-card`, ≥24/page).

## Stream sources (per video page) — GRAMMAR DRIFT, FIXED IN-RUN

Every sampled page embeds `//xiaoshenke.net/video/<videoId>/<n>` (videoId numeric **or**
alphanumeric, e.g. `0739433`, `e98d5f2ef1da6`). The embed page (7.9 KB, fetchable both
with the site referer and bare) **no longer contains a jwplayer `configs` block**; it now
builds a fluidPlayer `<video><source …>` DOM from two static locals:

```
var id = "0739433".split('').reverse().join('');        // videoId, reversed glyph-wise
var quality = parseInt("14");                          // bit mask: 1=360 2=480 4=720 8=1080
// embed's own btq(): 14 → 480/720/1080; media = `${prefix}/${id}/${q}` — prefix = //xiaoshenke.net/vid
```

**The provider's WebView hook — regex for `jwplayer("player").setup(configs)` — no longer
matches anything**, and every script tag on the embed carries dead
`type="065ce68a…-text/javascript"`, so the inline player never boots even in the WebView
path → null → `loadExtractor` fallback with no xiaoshenke row. Drift confirmed live
2026-10-16 → **fixed in-run instead of a point swap: new pure Parse
(`FullPornerParse.parseEmbedSources`) derives the whole playlist from the static
id/mask locals; loadLinks emits it first and keeps the WebView+loadExtractor chain as
fallback** (TDD: 6 red → green tests, fixtures above).

Resolved chain re-proof (probe instrument = TLS tier, per surface below) — videoID →
reversed id → mask from the embed page, one row per sampled video, checked on the actual
streams the fixed provider will emit:

| watch page | videoID | reversed | mask | qualities served |
|---|---|---|---|---|
| /watch/68dbb83586d5fe1a7e1a6be8 | 2844692 | 2964482 | 14 | 480/720/1080 |
| /watch/6abe4b962f78355914467fc2 | 0739433 | 3349370 | 14 | 480/720/1080 |
| /watch/6ad215650e5f775a6fbbc65a | e98d5f2ef1da6 | 6ad1fe2f5d89e | 2 | 480 only |

All sampled qualities → **206 `video/mp4`**, real MP4 ftyp magic, distinct path per video
✓. (Probe hygiene note: one intermediate verify run had stream-url overrides crossed
between videos — all URLs still individually proven 206; final run re-executed with
position-matched overrides, `RESULT: PASS`, transcript in the PR.)

- Headers: TLS-impersonated + `Referer: https://xiaoshenke.net/` **or**
  `https://fullporner.com/` both serve 206; **without Referer → 404** (hotlink wall, not a
  challenge). Provider emits `referer = mainUrl` ✓.

Poster chain re-resolution (unchanged mechanism, re-verified):
`https://xiaoshenke.net/vid/<videoId reversed>/720/i` → 302 →
`v7.xiaoshenke.net/s0/…720/i/…` → 200 image (reversed 0739433 = 3349370 verified 200).

## Headers / referer

- fullporner.com listing/video pages: nothing beyond TLS tier + UA (no cookies, no age
  gate, no unlock param; CF `cf-mitigated: challenge` only to plain curl from this IP).
- xiaoshenke.net embed: 200 without referer; `vid/<id>/<q>` streams: **Referer required**
  (either site) — provider ships it.

## Pagination

Search `?p=N` disjoint ✓; home rows `/home/N`, `/category/<slug>/N` disjoint ✓;
quick search absent. (Verify.sh note: one search query — "amateur" — surfaced two
*different* videos sharing the identical title "Raw Passion Unleashed: Hardcore Amateur
Blowjob" on p1 (…e1a6bee vs …e1a6be8): site-side near-duplicate content, not a pagination
or provider defect; verify re-run on clean queries → PASS.)

## Risks / blockers

- Both domains Cloudflare-challenge plain curl from this runner (suspected runner-IP
  class, #461 exclusion family) — in-app devices present residential TLS; **stream
  playback remains in-app-verifiable, tier evidence above is TLS-impersonated**.
- `/home/1` page-1 churn: none observed this pass (24/24 stable cards; disjoint p2).

## Verdict deltas

- Verdict: **ok → ok-drift** (embed-grammar drift confirmed live 2026-10-16, fixed in-run;
  first confirmed drift occurrence — no closed drift issue exists for this provider yet).
- #526 standing-issue condition: live-confirmed **fixed** (tags selector matches live
  grammar 3/3 pages); registry `issue` releases.
- Duration/year data-completeness gap (newly exposed by the site) — **fixed in-run**
  (`FullPornerParse.durationMinutes` via shared DurationParse; `FullPornerParse.year`
  from the UTC epoch), v15 → 16.
