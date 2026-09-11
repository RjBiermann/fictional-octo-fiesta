# FINDINGS — neporn.com (2026-09 audit)

## Verdict: OK

## Search
- `/search/red/` returns empty (no 'red' content — legit), `/search/anal/` fine: `div.list-videos div.item` ✓; async page-2 URL works.

## Stream
- video page flashvars `video_url: 'https://neporn.com/get_file/7/.../39025_720p.mp4/?v-acctoken=...'` → 302 → `https://data003.neporn.com/remote_control.php?...` → **206 video/mp4**.

## Update — issue #130 Data-completeness probe (video/39025/wright-for-anal)
- plot: `meta[property=og:description]` ✓ (transcript: content="The adorable Whitney Wright joins Hard...")
- year: schema.org ld+json `"uploadDate": "2026-03-18T17:35:00Z"` ✓ (regex on script[type=application/ld+json])
- actors: `div.info-content a[href*=/models/]` → Whitney Wright, Ramon Nomar ✓
- streams unchanged; verify.sh PASS (search 200/24 items, 2 video pages 200 + 206 video/mp4, all LoadResponse fields assigned).

## Update — issue #301 Correctness fix (video/35636)
- Model anchors inside `div.info-content` carry a `.button-info` span with the member's
  video count; jsoup `.text()` on the `<a>` yields e.g. "Marica Hase 32".
- Fix: `Parse.actors()` reads `a[href*=/models/] .name` only → ["Marica Hase"].
  TDD: `NepornParseTest` + fixture `info-content-35636.html` (old selector fails the
  equality assert; new passes).
- Full verify run 2026-09-11: search p1 200/24 cards; home p1+p2 200/24+24, 46 unique
  hrefs (zero ID overlap); both video pages 200, flashvars v-acctoken stream 302 → 206
  video/mp4; tags/actors/year/duration present on all sampled pages; related 21–22 cards.
  Remaining verify.sh FAILs are the pre-adjudicated harness artifacts (#301 PR quote):
  regex-DOM truncation of `div.item` titles (home dup + related title dups) and
  stereotyped badges ("hd"/"premium 4k") — real hrefs/IDs are unique.
