# FINDINGS-876 — AllClassicPorn: video.js `cfg` grammar restores Sources (S4 of #876)

Run: 2026-10-24 (`05-implement`, story S4 / #1319; parent #876, epic "AllClassicPorn
stream-source drift — restore Sources via a multi-grammar KVS match"). S1 (#1316), S2
(#1317) and S3 (#1318, PR #1322) landed before this story; **no provider or `shared/` code
changed here** — this is the evidence + regression gate. Provider stays at version 18.

Site: `allclassic.porn` (KVS / Kernel Video Sharing). Plain HTTP only from the CI runner
(Mozilla UA + `Referer: https://allclassic.porn/`, no JS, no cookies, no challenge) — ADR-0008.

**Verdict: `ok`.** The site-wide grammar drift reported in #876 is real and is now covered by
the merged multi-grammar chain: on every sampled page the legacy flashvars are **gone**
(`video_url` → `null`, which is exactly the owner's "no Sources" symptom), the video.js `cfg`
entry `videoUrl` resolves, and both live URLs below walk `get_file` → `302` → CDN
`remote_control.php` → `200/206 video/mp4`.

## 1. What drifted — exact grammar and keys

| surface | 2026-10-16 (audit #637) | 2026-10-24 (this run) |
|---|---|---|
| legacy inline `flashvars` `video_url:` | present on all 5 sampled pages | **absent** (`grep -c` = 0 on every sampled page) |
| legacy bracket `flashvars['video_url']` | present | **absent** |
| legacy `video_url_text` caption | bracket form on all 5 | **absent** |
| video.js `var cfg = { … }` | not present | **present on every sampled page, exactly 1 `videoUrl` entry** |

Keys matched by the provider (all through the shared resolver `KvsFlashvars`):

- source — camelCase `videoUrl` colon entry (`\bvideoUrl\s*:`) inside the inline
  `var cfg = { … }` block. It is the **only** stream key the page exposes now.
- quality — no caption key exists in the new grammar (`videoUrlText` absent), so the label is
  derived from the resolved URL's filename token `_(\d{3,4})p` → `480p` (the shared
  `KvsFlashvars.quality` fallback path, exercised live below).
- legacy ordering — `KvsFlashvars.source` tries the legacy colon/bracket form first and only
  then the cfg form, so a future page that carries both keeps resolving as before.

Prefix safety is not academic on this page: the same `cfg` object carries `posterUrl`,
`timelineUrl`, `statsUrl`, `relatedUrl`, `embedUrl` and **`vastUrl`** — a loose `\w*Url\s*:`
pattern would match those; the `\b`-anchored `videoUrl` key does not.

### The live `cfg` wrapper (verbatim, S4 capture)

This is the capture S1's unit-test comment deferred to this story (it previously modelled the
wrapper synthetically from the committed `video-205.html` fixture). Token strings are
`?v-acctoken=` values (short-lived, per-request) — shape is what matters:

```js
var cfg = {
        videoUrl: 'https://allclassic.porn/get_file/1/b9435a759638f5f84f41aad1aabe1785/0/205/205_480p.mp4/?v-acctoken=…',
        posterUrl: 'https://allclassic.porn/contents/videos_screenshots/0/205/preview.jpg',
        volume: parseFloat('0.5') || 0.5,
        timelineUrl: 'https://allclassic.porn/contents/videos_screenshots/0/205/timelines/mp4/224x126/{time}.jpg',
        timelineInterval: parseInt('10', 10) || 0,
        timelineCount: parseInt('463', 10) || 0,
        statsUrl: 'https://allclassic.porn/player/stats.php?embed=0&video_id=205',
        relatedUrl: 'https://allclassic.porn/related_videos_html/205/',
        embedEnabled: '1' === '1' && '0' !== '1' && '0' !== '2',
        embedUrl: 'https://allclassic.porn/embed/205',
        embedSize: [parseInt('640', 10) || 640, parseInt('480', 10) || 360],
        vastUrl: 'https://s.magsrv.com/v1/vast.php?idz=5059280',
        vastTimeout: (parseInt('10', 10) || 10) * 1000,
        adReplayAfter: parseInt('3', 10) || 0,
        adReplayType: parseInt('0', 10) || 0,
        adSkipDuration: parseInt('7', 10) || 0,
        adSkipTextTime: 'SKIP AD IN %time',
        adSkipText: 'SKIP AND GO TO THE VIDEO'
      };
```

No `?login` upsell URL appears in the new grammar (the old `video_alt_url` points at
`https://allclassic.porn/?login`); `loadLinks` emits only the primary `videoUrl` source, so
that exclusion is unchanged.

## 2. Transcripts — the owner's URL and a fresh feed video

Owner's failing URL (#876 round-1 answer) and one video picked from the current home feed
(`/page/1/`), same session, `Range: bytes=0-64` on the CDN hop so the transcript stays small.

### (a) `https://allclassic.porn/videos/205/debbie-does-dallas/`

```
$ curl -sS -A "$UA" -H 'Referer: https://allclassic.porn/' -o page.html -w '%{http_code} %{content_type}' \
    'https://allclassic.porn/videos/205/debbie-does-dallas/'
  → 200 text/html; charset=utf-8 (467735 bytes)
$ grep -o "videoUrl: '…'" page.html
  videoUrl: 'https://allclassic.porn/get_file/1/b9435a759638f5f84f41aad1aabe1785/0/205/205_480p.mp4/?v-acctoken=MTEwMHwxfDd8MDA3NTE0MWEzYzA2NDE5MjcxNzE0YzMwNzExNzhiNDQ34947810ef50a51f'
$ grep -c "video_url\s*:\s*'" page.html            # legacy colon form
  0
$ grep -c "flashvars\['video_url'\]" page.html     # legacy bracket form
  0
$ grep -c video_url_text page.html                 # quality caption (any form)
  0
$ curl -sS -A "$UA" -H 'Referer: https://allclassic.porn/' -D hop1.hdr -o /dev/null \
    'https://allclassic.porn/get_file/1/b9435a759638f5f84f41aad1aabe1785/0/205/205_480p.mp4/?v-acctoken=…'
  → 302
  HTTP/1.1 302 Found
  Location: https://cdn1.allclassic.porn/remote_control.php?file=CFhx_mnix5Wb-…-D0bm9MTYE.mp4&acctoken=ZmQ3Mzk1NzVmZGQzYzUyMDQyOTMyNDlmN2Y3Nzk1MDUyNGNjMWE3NjUzYWE2ODhmMTcyZTIzZGM5NDE2ODY5OXw…
$ curl -sSL -A "$UA" -H 'Referer: https://allclassic.porn/' -H 'Range: bytes=0-64' -D final.hdr -o body.bin \
    'https://cdn1.allclassic.porn/remote_control.php?file=CFhx_…&acctoken=ZmQ3…'
  → 206 video/mp4 (65 bytes)
  HTTP/1.1 206 Partial Content
  Server: nginx/1.22.1
  Content-Type: video/mp4
  Content-Length: 65
  Last-Modified: Tue, 13 Aug 2024 05:44:36 GMT
  first bytes: 000000206674797069736f6d00000200   ( ftypisom / isomiso2 — ISO-BMFF MP4 )
```

### (b) fresh feed video `https://allclassic.porn/videos/6788/wine-sucks-stranger/`

(picked from `/page/1/` this run — not one of the FINDINGS example videos)

```
$ curl -sS -A "$UA" -H 'Referer: https://allclassic.porn/' -o page.html -w '%{http_code} %{content_type}' \
    'https://allclassic.porn/videos/6788/wine-sucks-stranger/'
  → 200 text/html; charset=utf-8 (172717 bytes)
$ grep -o "videoUrl: '…'" page.html
  videoUrl: 'https://allclassic.porn/get_file/1/4ab8b4289c947c846026ea1df79649b8/6000/6788/6788_480p.mp4/?v-acctoken=NzAyfDE1MzE2fDB8ZjZmN2I4ZmE4N2Q3NzlkNDdkZTJjNzBkMGNiNTQxMjQ021a3a7a0aa5284f'
$ grep -c "video_url\s*:\s*'" page.html            # legacy colon form
  0
$ grep -c "flashvars\['video_url'\]" page.html     # legacy bracket form
  0
$ grep -c video_url_text page.html                 # quality caption (any form)
  0
$ curl -sS -A "$UA" -H 'Referer: https://allclassic.porn/' -D hop1.hdr -o /dev/null \
    'https://allclassic.porn/get_file/1/4ab8b4289c947c846026ea1df79649b8/6000/6788/6788_480p.mp4/?v-acctoken=…'
  → 302
  HTTP/1.1 302 Found
  Location: https://cdn1.allclassic.porn/remote_control.php?file=fu2X9EWQUO42sa48GX_…-axCH6qKRBKrw.mp4&acctoken=ZGJmMzFhNTMxMzdjNTExOWFkNDgzZTQ5YjlhMWFkNzI0MTQ5MDMyODI4NWU5Mjg2NjlkZjA2ODhkYTdiMjFmMXw…
$ curl -sSL -A "$UA" -H 'Referer: https://allclassic.porn/' -H 'Range: bytes=0-64' -D final.hdr -o body.bin \
    'https://cdn1.allclassic.porn/remote_control.php?file=fu2X9…&acctoken=ZGJm…'
  → 206 video/mp4 (65 bytes)
  HTTP/1.1 206 Partial Content
  Server: nginx/1.22.1
  Content-Type: video/mp4
  Content-Length: 65
  Last-Modified: Fri, 23 Oct 2026 14:37:24 GMT
  first bytes: 000000206674797069736f6d00000200   ( ftypisom / isomiso2 — ISO-BMFF MP4 )
```

### Control flow (unchanged by the grammar drift)

```
video page (200, cfg videoUrl)
  → GET https://allclassic.porn/get_file/1/<hash>/<bucket>/<id>/<id>_480p.mp4/?v-acctoken=…
      302 Found, Location: https://cdn<1|3>.allclassic.porn/remote_control.php?file=…&acctoken=…
  → GET that remote_control.php URL
      (a) with a full body GET and no Range: 302 again to the same
          remote_control.php with a refreshed acctoken, then 200 video/mp4
          (observed on the 205/6688/6788 full-body fetches this run), or
      (b) with Range: bytes=0-64: 206 video/mp4 directly (transcript above).
```

Both hops stay inside `allclassic.porn` → `cdn*.allclassic.porn`; there is no region-, cookie-
or auth-dependent branch, so the chain is VPN-neutral (the owner's requirement) — the only
per-request state is the `v-acctoken`/`acctoken` pair the site mints itself. Referer is sent
throughout; a no-referer request also 302s (FINDINGS, 2026-09-09), so headers are belt-and-braces,
not a gate.

## 3. Provider-level resolution proof (not just grep)

`AllClassicPornParse.parseVideoUrl`/`parseQuality` delegate to the shared resolver, so the
shared module was compiled standalone (kotlinc, no Android/jsoup) and run over all five live
pages captured this session:

```
p1.html (/videos/205/debbie-does-dallas/)
  legacy field(video_url)   = null
  source() (chain)          = https://allclassic.porn/get_file/1/b9435a…/0/205/205_480p.mp4/?v-acctoken=MTEwMHwxfDd
  quality()                 = 480p
  page has var cfg = {      = true
p2.html (/videos/6788/wine-sucks-stranger/)
  legacy field(video_url)   = null
  source() (chain)          = …/4ab8b4289c947c846026ea1df79649b8/6000/6788/6788_480p.mp4/?v-acctoken=NzAyfD
  quality()                 = 480p
p3.html (/videos/6460/peacock-lady/)          source() = …/6460/6460_480p.mp4…   quality() = 480p
p4.html (/videos/3887/homemade-video-of-…)    source() = …/3887/3887_480p.mp4…   quality() = 480p
p5.html (/videos/1215/two-hearts-…)           source() = …/1215/1215_480p.mp4…   quality() = 480p
```

`legacy field = null` on every page is the pre-S1/S2 behaviour verbatim — that null is what
made `loadLinks` `return false` (owner's "no Sources"). The chain now resolves; quality comes
from the filename token because the new grammar has no caption key.

## 4. Pipeline verification — `verify.sh` transcript

Run from the repo root against the live site (5 varied video pages: owner's URL, two from
`/page/1/`, one from `/search/milf/` so check-5 agreement is exercised, one from `/90s/`):

```
── check 1: search pages (2)
GET https://allclassic.porn/search/milf/ → 200; 'a.th.item' matches: 60
GET https://allclassic.porn/search/milf/2/ → 200; 'a.th.item' matches: 60
── check 1a: homepage pages (2)
GET https://allclassic.porn/page/1/ → 200; 'a.th.item' matches: 60
GET https://allclassic.porn/page/2/ → 200; 'a.th.item' matches: 60
── check 1b: quick search (0)
NOTE: no --quick-search-url — FINDINGS records no distinct quick-search endpoint (audit #204)
── check 2: video pages (5 URLs)
GET https://allclassic.porn/videos/205/debbie-does-dallas/ → 200; 'script' matches: 35
GET stream (https://allclassic.porn/get_file/1/b9435a…/0/205/205_48…) → 206 video/mp4
GET …/videos/205/… → 'div#list_videos_related_videos_items a.th.item' matches: 30
GET https://allclassic.porn/videos/6788/wine-sucks-stranger/ → 200; 'script' matches: 35
GET stream (https://allclassic.porn/get_file/1/4ab8b4…/6000/6788/67…) → 206 video/mp4
GET …/videos/6788/… → 'div#list_videos_related_videos_items a.th.item' matches: 30
GET https://allclassic.porn/videos/6460/peacock-lady/ → 200; 'script' matches: 35
GET stream (https://allclassic.porn/get_file/1/5efa66…/6000/6460/64…) → 206 video/mp4
GET …/videos/6460/… → related matches: 30
GET https://allclassic.porn/videos/3887/homemade-video-of-milf-lesbians-filmed-by-the-husband/ → 200; 'script' matches: 35
GET stream (https://allclassic.porn/get_file/1/e61200…/3000/3887/38…) → 206 video/mp4
GET …/videos/3887/… → related matches: 30
GET https://allclassic.porn/videos/1215/two-hearts-gloria-leonard-s-body-and-soul/ → 200; 'script' matches: 35
GET stream (https://allclassic.porn/get_file/1/49f30c…/1000/1215/12…) → 206 video/mp4
GET …/videos/1215/… → related matches: 30
NOTE: no --video-tags/actors/year/duration-selector — KVS flashvars/h1 exposure (audit #205)
── check 5: search ↔ load agreement
FAIL poster mismatch for /videos/3887/homemade-video-of-milf-lesbians-filmed-by-the-husband:
  search='…/videos_screenshots/3000/3887/320x240/26.jpg' load='…/videos_screenshots/3000/3887/preview.jpg'
── check 6: LoadResponse completeness (code vs FINDINGS)
field 'recommendations': 2 assignment(s)   field 'tags': 3   field 'plot': 3
field 'duration': 2   field 'year': 1   field 'actors': 3
RESULT: FAIL
```

**The single FAIL is the standing site-side artifact, not a provider defect** (audit #204
artifact 2, re-confirmed by #266/#289/#637): the search card serves a screenshot thumbnail
`…/320x240/<random N>.jpg` while the load page's only stable poster is `og:image`
`…/preview.jpg`. The card's `N` is re-randomised per render, so no code change can equalize
the two surfaces. Everything else passes:

- title agreement **passes** with the provider-faithful pair `--search-title-selector
  div.th-description` ↔ `--video-title-selector h1[itemprop=name]` (no title mismatch printed —
  the #204 h1 fix still holds);
- related videos: `div#list_videos_related_videos_items a.th.item` → 30 per page, no self-hit,
  no duplicate hrefs (the old tag-less `#id a.th.item` selector's mini-DOM `-1` artifact is
  avoided by prefixing the `div` tag — noted here for future runs);
- stream overrides (the JS-built `cfg` URL per page, exactly what the provider resolves):
  5/5 → `206 video/mp4`, distinct paths;
- cross-video identity distinctness (title/poster/plot/stream/href): no collisions;
- no duplicate cards within or across the two search pages / two home pages.

## 5. Regression note — the drift window (bounded, not invented)

"Last known good version unknown" (round-1 answer) can now be bracketed by evidence rather than
guessed:

- **2026-10-16 — last live observation of the legacy grammar.** Audit #637's re-probe recorded
  the flashvars `video_url` **colon form present on all 5** sampled pages (its finding was that
  the *caption* had narrowed to the bracket form only).
- **2026-10-23T02:11Z — first live observation of the cfg-only grammar** (plan round-2 probe,
  run 39888234688 on #876: "zero `video_url:` entries … player moved to a video.js `cfg`").
- **2026-10-24 (this run) — site-wide.** All 5 freshly sampled pages plus three historical
  FINDINGS fixtures re-fetched live (6161, 2252, 5887) carry `var cfg = { videoUrl: … }` and
  zero legacy `video_url` / `video_url_text` entries.

So the grammar flipped site-wide inside **2026-10-16 → 2026-10-23**, and it flipped for
*every* video at once (per-video pages that historically served different grammars — 2252/1573/549
bracket-only, 6161/6403 both forms (audit #322 D2) — are now uniformly cfg-only). That matches
the owner's symptom shape exactly: an app update is not required for breakage, no page plays,
and the page itself still returns 200 — a pure rendering-grammar change on the site side. It also
means the previous single-grammar reads (`KvsFlashvars.field(html, "video_url")`) were guaranteed
dead from that window onward, which is why the fix had to be a grammar addition rather than a
per-page selector touch-up.

## 6. Build + cross-provider regression gate

`KvsFlashvars` is shared by **Neporn, Javbangers, Porntrex** — the added grammar is additive
(legacy match preferred), so their resolution must be unchanged:

| task | result |
|---|---|
| `./gradlew :AllClassicPorn:test` | **BUILD SUCCESSFUL** — 240 tests, 0 failures (includes the spliced `shared/` suite, `KvsFlashvarsTest` cfg cases among them) |
| `./gradlew :AllClassicPorn:make` | **BUILD SUCCESSFUL** — clean `.cs3`, provider v18 |
| `./gradlew :Neporn:test` | **BUILD SUCCESSFUL** — 196 tests, 0 failures |
| `./gradlew :Javbangers:test` | **BUILD SUCCESSFUL** — 208 tests, 0 failures |
| `./gradlew :Porntrex:test` | **BUILD SUCCESSFUL** — 216 tests, 0 failures |
| `./gradlew :Neporn:make :Javbangers:make :Porntrex:make` | **BUILD SUCCESSFUL** |

No sibling regression, so nothing to hand to a sibling story.

## 7. Carried observations (no action needed here)

1. The shared `KvsFlashvarsTest` comment that called the live `cfg` wrapper "pending S4 live
   capture" is now satisfied by §1 above; the comment was updated to point at this record
   (comment-only, no test or logic change).
2. `verify.sh`'s static stream extractor still only knows the legacy `video_url:` pattern, so the
   cfg-only page makes it fall back to `og:video` (embed iframe → `text/html`). This run used
   `--stream-url` overrides (the provider-resolved `cfg` URL per page), which is the documented
   mechanism for JS-built streams. Teaching the script the `videoUrl:` pattern would be a
   verify-skill change, out of scope for a provider story.
3. `AllClassicPorn/FINDINGS.md` now carries a dated #876 section so the provider doc no longer
   claims the stream source is the inline flashvars (`video_url:` only).
