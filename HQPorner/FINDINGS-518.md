# FINDINGS-518 — HQPorner video-page poster missing (black details page)

Issue: #518 — "video load shows no poster, black in video details page; feed posters fine."

## Live state (probed 2026-ish, runner IP country, outlook/see Risks)

Machine: plain curl, desktop UA `Mozilla/5.0 (…rv:130.0 …Firefox/130.0)`. All fetches 200.

### What still works (no drift in the feed path)
- Search `?q=maria+ozawa&p=1` → 200, cards `div.row section:has(a.image)`; card anchor `href="/hdporn/39719-Maria_Ozawa_2.html"`, `img id="cover_39719" src="//fastporndelivery.hqporner.com/imgs/thumbs/14/29/b73b1d8b4f15e18_main.jpg" alt="Maria Ozawa 2"`.
- Homepage `/top/1` → same card shape (`a.image featured non-overlay atfib …`, `cover_<id>` img).
- So the search/homepage → details poster handoff (`href + "kraptor" + posterUrl` split in
  `load()`) is structurally intact, and the image host serves without any headers:

  `curl https://fastporndelivery.hqporner.com/imgs/thumbs/14/29/b73b1d8b4f15e18_main.jpg` →
  **200 image/jpeg** with full UA, truncated UA, and with **no headers at all** (verified ×6).
  Toolbar-referer and truncated UA in `load()`'s posterHeaders are not the cause.

### The gap: the video page has no poster of its own
- `/hdporn/124624-just_to_look_at_me.html` (200): no `og:image`/thumbnail meta, grep for
  `og:image|thumbnail|poster|data:image` → empty. Every `img[src*=imgs]`/`cover_<id>` on the
  page belongs to **other** videos (ids 120561/118892/98385/91493/23755 — related + recent).
  `124624` appears only in canonical/alternate links. No `preload_124624` JS array.
- `m.hqporner.com` mobile variant → 200, same: no own image, no og tags.
- Transcript: `grep -c 'og:image' /tmp/hqp_video.html` → 0; `grep -oE '[^" ]*(_main\.jpg|124624)[^" ]*'` → thumbs of 120561/118892/98385/91493/23755 only.
- Therefore any `load()` that doesn't receive the feed-embedded poster (library reload,
  deep link, history) sets `posterUrl = null` → **black details page**. This is the
  reproducible failure: `load("https://hqporner.com/hdporn/124624-just_to_look_at_me.html")`
  has no poster path in current code. Prior FINDINGS (#237) had ruled "no truthful fallback
  exists" — that was wrong: the fallback was simply on the player embed.

### The truthful poster source (new finding): the player embed

Probed `/hdporn/124624-just_to_look_at_me.html` → mydaddy iframe `//mydaddy.cc/video/1cc592e0dd99de5fca/`:

  `curl https://mydaddy.cc/video/1cc592e0dd99de5fca/ -H "Referer: https://hqporner.com/"` →
  `poster=\"//s62.bigcdn.cc/pubs/6ac80831a84bc1.47204963/main.jpg\"`

  `curl -w '%{http_code} %{content_type} %{size_download}' https://s62.bigcdn.cc/pubs/6ac80831a84bc1.47204963/main.jpg`
  → **200 image/jpeg 137891** (no special headers needed).

Older upload `/hdporn/39719-Maria_Ozawa_2.html` (hqwo.cc embed, issue #431): the iframe src
itself carries the cover, base64:

  `src="//hqwo.cc/player/b73b1d8b4f15e184a5c6800d7a18a307?img=Ly9ocXBvcm5lci5jb20vaW1ncy90aHVtYnMvMTQvMjkvYjczYjFkOGI0ZjE1ZTE4X2NvdmVyLmpwZw=="` →
  base64-decode → `//hqporner.com/imgs/thumbs/14/29/b73b1d8b4f15e18_cover.jpg` →
  **200 image/jpeg 59330** (no headers).

Both are the *current video's* own cover, not another card's.

## Fix shape
In `load()`, when the feed-embedded poster is absent (`parts.getOrNull(1)` null — library
reload / direct open), resolve the poster from the page's player iframe: read the iframe src
(same selectors `loadLinks` already uses), fetch it once, take `img=<b64>` (hqwo) decoded, else
`poster="…"` (mydaddy). Feed→load path keeps the existing kraptor poster (no extra requests).

## Risks / blockers
- None hit: no challenge, image/CDN hosts serve from bare curl. Desktop-UA pin (below) unchanged.

## Fix (this repo, PR branch)

`load()` now falls back to the player-poster when the feed-embedded poster is absent:
`PlayerPosterParse.fromSrc(iframe)` (hqwo: `img=<b64>`), else fetch the embed once and
`PlayerPosterParse.fromBody` (mydaddy: `poster="…//…/main.jpg"`). Feed→load path unchanged
(kraptor poster, zero extra requests). Fixture-taken red → green at
`HQPorner/src/test/kotlin/com/kraptor/ParseTest.kt` (+ `issue518/` fixtures);
version 13 → 14.

## Live verify (verify.sh, desktop UA, p2 headers)

- search p1/p2 → 200, 10/50 `a.image` matches.
- homepage /top/1, /top/2 → 200, 50/50 matches (page-2 items differ from search flood).
- video ×5 (top-recent ×2, search ×1, hqwo-legacy ×1, related ×1) → 200 each,
  duration/tags/actors/plot extract from every page, related `div[class=4u] section` 16–47.
- streams (per-video key from the embed chain): 5/5 unique → **206 video/mp4**.
- RESULT: FAIL carries only the documented script ceilings, also present pre-fix and
  recorded in FINDINGS-431/FINDINGS.md: ① one `/hdporn/128052-…` video repeats on search
  p1→p2 via the top-rated **sidebar** outside the provider's `div.row` card filter
  (provider-invisible); ② card title `img alt` / no og:image — the script's search-title and
  poster-column checks can't express them; manual search↔load agreement holds (alt
  "Maria Ozawa 2" ↔ load h1 "maria ozawa 2" ✓).
- Fix evidence: each of the 5 embed pages carries the current video's own cover —
  mydaddy `poster=\"//s(?:49|2|62).bigcdn.cc/pubs/<key>/main.jpg\"`, hqwo
  `img=<b64>` → `//hqporner.com/imgs/thumbs/14/29/b73b1d8b4f15e18_cover.jpg` — all
  200 image/jpeg from bare curl. The playerPoster fallback therefore resolves on every
  sampled page; the details-page poster no longer depends on the kraptor handoff.
