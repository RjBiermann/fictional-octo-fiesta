# FINDINGS — issue #509 — verify.sh instrument: card-title clip + home-dedupe conflation

Issue (filed from FINDINGS-506.md / PR of #506): two verify.sh bugs false-FAIL any
re-check against KVS themes (repro Porntrex):
1. card `<div>` block regex is close-tag-agnostic → clipped card titles ("1080p HD");
2. homepage dedupe counts site-side catalog repeats (19 ids on `/categories/hardcore/`
   async page 1 vs page 2) as a provider parse FAIL.

Probed: 2026-10-13 (this run), runner IP, plain curl — evidence recomputed FRESH, not
copied from #506. UA: Firefox 130 desktop.

## Repro 1 — card-title clip (title-fallback + chain selectors)

Live card shape (Porntrex `/categories/hardcore/` page 1, first card):

```html
<div class="video-preview-screen video-item thumb-item  " data-item-id="3349742">
<a href="https://www.porntrex.com/video/3349742/missax-addison-vodka-masquerade" class="thumb">
  <img class="cover lazyload" data-src="//ptx.cdntrex.com/…/3349742/300x168/1.jpg?v=3" alt="MissaX - Addison Vodka - Masquerade"/>
  …
</a>
<p class="inf"><a>…real title…</a></p>
</div>
```

### Before the fix (verified against `git show HEAD:` of verify.sh, same live page)

```bash
python3 pydom_old.py cards 'div.video-preview-screen.video-item' - - hardcore.html | cut -f2 | head -3
# → 1080p HD
# → 1080p HD
# → 1080p HD
```

Every card's default title was the clipped inner_text of the quality-label div. With
`p.inf a` as the title selector the OLD code reduced the chain to its last part (`a`)
and matched the FIRST `<a>` inside the clipped block — the thumb anchor whose text is
the quality icon ("1080p HD"), never `p.inf`. Exactly the verify-506.log regime:
`title 1080p hd home0,home0,… x96` — those "96 identical cards" were the clipped-title
artifact, not the catalog.

### After the fix

`blocks()` in `.pi/skills/verify-provider/scripts/verify.sh` now (a) balances the
block's own tag (depth walk to the matching close, not first-close truncation) and
(b) honors the FULL descendant chain by narrowing through every ancestor part:

```bash
python3 pydom_new.py cards 'div.video-preview-screen.video-item' 'p.inf a' 'img.cover' hardcore.html | cut -f1-3 | head -2
# → https://www.porntrex.com/video/3349742/…  [real title]  //ptx.cdntrex.com/…/3349742/300x168/1.jpg?v=3
# → https://www.porntrex.com/video/3349826/…  [real title]  …
```

Corroborated live on the search page (`/search/milf/`, 85 cards, static + `from=2`
async): chain title extraction returns real per-card titles with matching covers.

Offline parser fixture (networkless; single runnable check):
`.pi/skills/verify-provider/scripts/test_pydom.sh` — asserts nested-div cards don't
clip, chain `p.inf a` returns the real title, the poster still resolves through
lazyload `data-src`, and the within/cross duplicate partition is href-keyed.

## Repro 2 — site-side catalog duplicates (Hardcore home row)

```bash
curl -sL -A $UA https://www.porntrex.com/categories/hardcore/          # 200, 120 cards
curl -sL -A $UA -H 'X-Requested-With: XMLHttpRequest' \
  -e https://www.porntrex.com/categories/hardcore/ \
  'https://www.porntrex.com/categories/hardcore/?mode=async&function=get_block&block_id=list_videos_common_videos_list_norm&from=2'
# 200, 120 cards; 19 data-item-ids SHARED with page 1
# (sample: 3348948, 3348953, 3348957, 3348962, 3348968, 3348973, 3348990, 3348991 …)
# zero duplication WITHIN each page; same card order on both pages.
curl -sL -A $UA -e https://www.porntrex.com/ https://www.porntrex.com/categories/hardcore/2/
# 200, 120 cards; contains the SAME 19 ids — the site's own static page 2 lists them.
# Control /categories/4k-porn/ (static + async from=2): 120 + 120, shared = 0.
```

The provider parses each card once (`div.video-preview-screen.video-item` →
`p.inf a`, `a.thumb[href]`, `img.cover[data-src]` — Porntrex.kt:78,87-92), so the
overlap comes from the site's own async page-strip ordering (KVS `from=1`/`from=2`
boundary re-listing a card run), not a provider parse defect. Matches #506's live
finding, re-verified with fresh fetches since the catalog moves.

## Fix (verify.sh, one file, no provider code touched)

1. `blocks()`: same-tag close-tag balancing + full descendant-chain narrowing.
2. Listing duplicate detection keys on **href** and splits partitions:
   within-page repeats stay **FAIL**; duplicates straddling pages become a **NOTE**
   with the duplicate list as evidence when `--cross-page-dups-note` is passed
   (FINDINGS-declared: this file; Porntrex Hardcore row) — a repeat WITHIN one page
   stays a FAIL even with the flag.
3. `title` collisions dropped from the FAIL column (different hrefs = different
   videos may legitimately share a title) — dumped to a NOTE instead. The pre-fix
   "96 same-value title collisions" can't come back (that regime required clipping,
   which the balanced walk removes); a repeated-title NOTE stays the visible trace.

SKILL.md updated: `--cross-page-dups-note` documented as
FINDINGS-authorization-required, enforced home-only in the script (passing it for
search listings has no effect — a search page 2 repeating page 1 stays a defect FAIL).

## Verification (post-fix, live, verify-provider skill, this run)

`verify.sh` run: search milf page 1/2 (`/search/milf/` + async `from=2`), home
`/categories/hardcore/` static + async `from=2`, `--home-selector 'div.video-preview-screen.video-item'`,
5 fresh video URLs sampled from the LIVE home row catalog (all 200), stream selector
`div.video-info`, headers, `--cross-page-dups-note`. Transcript `/tmp/verify-509.log`.

- search page 1/2: 200, 85 + 85 cards, real per-card titles via `p.inf a`, page 2
  fresh (no href overlap) → dedupe clean.
- home page 1/2: 200, 120 + 120 cards; the 19-id cross-page repeat downgrade fired as
  designed:
  ```text
  NOTE: duplicated cards across home pages (FINDINGS-declared site-side repeat;
  provider parses each card once) — 19 href(s):
      href  /video/3348948/hijabmylfs-karter-foxx2   home0,home1
      href  /video/3348953/devicebondage-charlee-chaste-pushed-to-the-edge-…  home0,home1
      …
  ```
  No within-page duplicate FAILs. No title-collision FAILs (the old "1080p HD x96"
  regime is gone — repeated-title NOTE lists only the 19 genuine site repeats).
- distinct bar across the 5 sampled videos: titles, posters, plots, stream paths all
  pairwise distinct — no FAIL.
- streams (≤5 per page, first each): every `div.video-info`-embedded `video_url`
  fresh-extracted — **HTTP 206 video/mp4** on all 5 videos.
- related listings: Porntrex exposes none (per FINDINGS; not asserted here).
- **RESULT: PASS** (the #506 run of the same suite FAILed — false-FAILs proven gone
  on the live site).

## Raw probe values (this run, runner IP, 2026-10-13)

- `/categories/hardcore/` page 1: 719,917 B HTML, 120 cards, first id 3349742.
- async `from=2` strip: 301,175 B, 120 cards; shared with page 1: 19; page-1+2 card
  order identical for the shared run.
- static `/categories/hardcore/2/`: 120 cards; holds the same 19 ids.
- `/categories/4k-porn/`: 120 + 120, shared 0 (control stays clean).
- `/search/milf/`: 85 + 85 cards across page 1/async 2.
- 5 sampled video pages: all 200, each embeds one KVS `video_url` (fresh token per
  request); every sampled stream URL serves `206 video/mp4` with `Range: bytes=0-64`.

## Out of scope (left as-is, documented)

- Provider Kotlin: correct all along; the instrument was broken. No provider change,
  no version bump.
- Deep selector ancestry (only last-part + chain narrowing implemented; CSS
  nth-child style pseudo-classes stay unsupported in the regex "DOM").
- Self-closing block targets (`<img>`, `<source>`) remain open-tag-counted (attr
  extraction path unchanged) — closed-element balancing only applies where a real
  closing tag is expected.
